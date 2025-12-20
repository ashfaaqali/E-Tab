package org.weproz.etab.util

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.siegmann.epublib.epub.EpubReader
import org.weproz.etab.data.local.entity.TextNoteEntity
import org.weproz.etab.data.model.whiteboard.DrawAction
import org.weproz.etab.data.model.whiteboard.ParsedPage
import org.weproz.etab.data.serializer.WhiteboardSerializer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Helper class for sharing files via Bluetooth
 */
object ShareHelper {

    /**
     * Share an existing PDF or EPUB file via Bluetooth
     * Shares the file directly without conversion
     */
    fun shareBookViaBluetooth(context: Context, filePath: String, title: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
                return
            }

            val mimeType = if (filePath.endsWith(".epub", ignoreCase = true)) {
                "application/epub+zip"
            } else {
                "application/pdf"
            }

            shareFileViaBluetooth(context, file, mimeType, title)

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Failed to share: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Convert whiteboard to PDF and share via Bluetooth
     */
    suspend fun shareWhiteboardAsPdf(context: Context, dataPath: String, title: String) {
        withContext(Dispatchers.IO) {
            try {
                val pdfFile = createWhiteboardPdf(context, dataPath, title)

                withContext(Dispatchers.Main) {
                    shareFileViaBluetooth(context, pdfFile, "application/pdf", title)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to create PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Convert text note to PDF and share via Bluetooth
     */
    suspend fun shareTextNoteAsPdf(context: Context, note: TextNoteEntity) {
        withContext(Dispatchers.IO) {
            try {
                val pdfFile = createTextNotePdf(context, note)

                withContext(Dispatchers.Main) {
                    shareFileViaBluetooth(context, pdfFile, "application/pdf", note.title)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to create PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareFileViaBluetooth(context: Context, file: File, mimeType: String, title: String) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                setPackage("com.android.bluetooth")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                // Bluetooth not available, show chooser
                val chooser = Intent.createChooser(intent.apply { setPackage(null) }, "Share via")
                context.startActivity(chooser)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Failed to share: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createTextNotePdf(context: Context, note: TextNoteEntity): File {
        val pdfDocument = PdfDocument()

        val pageWidth = 595 // A4 width in points
        val pageHeight = 842 // A4 height in points
        val margin = 50f
        val lineHeight = 20f

        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 24f
            isFakeBoldText = true
        }

        val contentPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 14f
        }

        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas = page.canvas

        var yPos = margin + 30f

        // Draw title
        canvas.drawText(note.title, margin, yPos, titlePaint)
        yPos += 50f

        // Draw content - wrap text
        val content = note.content
        val words = content.split(" ")
        var line = StringBuilder()
        val maxWidth = pageWidth - 2 * margin

        for (word in words) {
            val testLine = if (line.isEmpty()) word else "$line $word"
            val textWidth = contentPaint.measureText(testLine)

            if (textWidth > maxWidth) {
                // Draw current line
                canvas.drawText(line.toString(), margin, yPos, contentPaint)
                yPos += lineHeight
                line = StringBuilder(word)

                // Check if we need a new page
                if (yPos > pageHeight - margin) {
                    pdfDocument.finishPage(page)
                    val newPageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pdfDocument.pages.size + 1).create()
                    page = pdfDocument.startPage(newPageInfo)
                    canvas = page.canvas
                    yPos = margin
                }
            } else {
                line = StringBuilder(testLine)
            }
        }

        // Draw remaining text
        if (line.isNotEmpty()) {
            canvas.drawText(line.toString(), margin, yPos, contentPaint)
        }

        pdfDocument.finishPage(page)

        // Save to file
        val fileName = "${note.title.replace("[^a-zA-Z0-9]".toRegex(), "_")}_${System.currentTimeMillis()}.pdf"
        val outputFile = File(context.cacheDir, fileName)
        FileOutputStream(outputFile).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()

        return outputFile
    }

    private fun createWhiteboardPdf(context: Context, dataPath: String, title: String): File {
        val file = File(dataPath)
        if (!file.exists()) {
            throw Exception("Whiteboard data not found")
        }

        val json = file.readText()
        val parsedData = WhiteboardSerializer.deserialize(json)
        val pages = parsedData.pages

        val pdfDocument = PdfDocument()
        // Use A4 size
        val pageWidth = 595
        val pageHeight = 842

        if (pages.isEmpty()) {
            // Create empty page
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            page.canvas.drawColor(Color.WHITE)
            pdfDocument.finishPage(page)
        } else {
            for ((index, parsedPage) in pages.withIndex()) {
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)

                // Calculate bounds of the content
                var minX = Float.MAX_VALUE
                var maxX = Float.MIN_VALUE
                var minY = Float.MAX_VALUE
                var maxY = Float.MIN_VALUE
                var hasContent = false

                for (action in parsedPage.actions) {
                    when (action) {
                        is DrawAction.Stroke -> {
                            for (point in action.points) {
                                minX = minOf(minX, point.first)
                                maxX = maxOf(maxX, point.first)
                                minY = minOf(minY, point.second)
                                maxY = maxOf(maxY, point.second)
                                hasContent = true
                            }
                        }
                        is DrawAction.Text -> {
                            minX = minOf(minX, action.x)
                            maxX = maxOf(maxX, action.x + action.textSize * action.text.length * 0.6f) // Approx width
                            minY = minOf(minY, action.y - action.textSize)
                            maxY = maxOf(maxY, action.y)
                            hasContent = true
                        }
                    }
                }

                if (hasContent) {
                    // Add some padding to bounds
                    val padding = 20f
                    minX -= padding
                    minY -= padding
                    maxX += padding
                    maxY += padding

                    val contentWidth = maxX - minX
                    val contentHeight = maxY - minY

                    // Calculate scale to fit in A4
                    val scaleX = pageWidth.toFloat() / contentWidth
                    val scaleY = pageHeight.toFloat() / contentHeight
                    val scale = minOf(scaleX, scaleY, 1.0f) // Don't scale up, only down if needed

                    // Center content
                    val translateX = (pageWidth - contentWidth * scale) / 2f - minX * scale
                    val translateY = (pageHeight - contentHeight * scale) / 2f - minY * scale

                    canvas.save()
                    canvas.translate(translateX, translateY)
                    canvas.scale(scale, scale)
                }

                // Draw each action from the page
                for (action in parsedPage.actions) {
                    when (action) {
                        is DrawAction.Stroke -> {
                            val paint = Paint().apply {
                                color = action.color
                                strokeWidth = action.strokeWidth
                                style = Paint.Style.STROKE
                                strokeCap = Paint.Cap.ROUND
                                strokeJoin = Paint.Join.ROUND
                                isAntiAlias = true
                            }

                            val path = android.graphics.Path()
                            val points = action.points
                            if (points.isNotEmpty()) {
                                path.moveTo(points[0].first, points[0].second)
                                for (i in 1 until points.size) {
                                    path.lineTo(points[i].first, points[i].second)
                                }
                            }
                            canvas.drawPath(path, paint)
                        }
                        is DrawAction.Text -> {
                            val paint = Paint().apply {
                                color = action.color
                                textSize = action.textSize
                                isAntiAlias = true
                            }
                            canvas.drawText(action.text, action.x, action.y, paint)
                        }
                    }
                }

                if (hasContent) {
                    canvas.restore()
                }

                pdfDocument.finishPage(page)
            }
        }

        // Save to file
        val fileName = "${title.replace("[^a-zA-Z0-9]".toRegex(), "_")}_${System.currentTimeMillis()}.pdf"
        val outputFile = File(context.cacheDir, fileName)
        FileOutputStream(outputFile).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()

        return outputFile
    }
}

