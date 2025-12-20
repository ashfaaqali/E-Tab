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
import org.weproz.etab.data.local.TextNoteEntity
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
     * For EPUB, converts to PDF first since Bluetooth may not support EPUB
     */
    fun shareBookViaBluetooth(context: Context, filePath: String, title: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
                return
            }

            if (filePath.endsWith(".epub", ignoreCase = true)) {
                // Convert EPUB to PDF and share
                Toast.makeText(context, "Converting to PDF...", Toast.LENGTH_SHORT).show()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val pdfFile = convertEpubToPdf(context, filePath, title)
                        withContext(Dispatchers.Main) {
                            shareFileViaBluetooth(context, pdfFile, "application/pdf", title)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Failed to convert: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                // Share PDF directly
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    setPackage("com.android.bluetooth")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    val chooser = Intent.createChooser(intent.apply { setPackage(null) }, "Share via")
                    context.startActivity(chooser)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Failed to share: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Convert EPUB to PDF
     */
    private fun convertEpubToPdf(context: Context, epubPath: String, title: String): File {
        val epubReader = EpubReader()
        val book = epubReader.readEpub(FileInputStream(epubPath))

        val pdfDocument = PdfDocument()
        val pageWidth = 595 // A4 width
        val pageHeight = 842 // A4 height
        val margin = 50f
        val lineHeight = 18f

        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 24f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val contentPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 12f
            isAntiAlias = true
        }

        val chapterTitlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 18f
            isFakeBoldText = true
            isAntiAlias = true
        }

        var pageNumber = 1
        var currentPage = pdfDocument.startPage(
            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        )
        var canvas = currentPage.canvas
        var yPos = margin + 30f

        // Draw book title on first page
        canvas.drawText(book.title ?: title, margin, yPos, titlePaint)
        yPos += 50f

        val maxWidth = pageWidth - 2 * margin

        // Process each spine item (chapter)
        for (spineRef in book.spine.spineReferences) {
            val resource = spineRef.resource
            if (resource != null) {
                try {
                    val content = String(resource.data)
                    // Strip HTML tags to get plain text
                    val plainText = content
                        .replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "")
                        .replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "")
                        .replace(Regex("<[^>]+>"), " ")
                        .replace(Regex("&nbsp;"), " ")
                        .replace(Regex("&amp;"), "&")
                        .replace(Regex("&lt;"), "<")
                        .replace(Regex("&gt;"), ">")
                        .replace(Regex("&quot;"), "\"")
                        .replace(Regex("\\s+"), " ")
                        .trim()

                    if (plainText.isEmpty()) continue

                    // Check if we need a new page for chapter
                    if (yPos > pageHeight - margin - 50) {
                        pdfDocument.finishPage(currentPage)
                        pageNumber++
                        currentPage = pdfDocument.startPage(
                            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                        )
                        canvas = currentPage.canvas
                        yPos = margin
                    }

                    // Draw chapter content
                    val words = plainText.split(" ")
                    var line = StringBuilder()

                    for (word in words) {
                        if (word.isEmpty()) continue

                        val testLine = if (line.isEmpty()) word else "$line $word"
                        val textWidth = contentPaint.measureText(testLine)

                        if (textWidth > maxWidth) {
                            canvas.drawText(line.toString(), margin, yPos, contentPaint)
                            yPos += lineHeight
                            line = StringBuilder(word)

                            // Check if we need a new page
                            if (yPos > pageHeight - margin) {
                                pdfDocument.finishPage(currentPage)
                                pageNumber++
                                currentPage = pdfDocument.startPage(
                                    PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                                )
                                canvas = currentPage.canvas
                                yPos = margin
                            }
                        } else {
                            line = StringBuilder(testLine)
                        }
                    }

                    // Draw remaining text
                    if (line.isNotEmpty()) {
                        canvas.drawText(line.toString(), margin, yPos, contentPaint)
                        yPos += lineHeight * 2 // Extra space after chapter
                    }
                } catch (e: Exception) {
                    // Skip problematic chapters
                    e.printStackTrace()
                }
            }
        }

        pdfDocument.finishPage(currentPage)

        // Save to file
        val fileName = "${title.replace("[^a-zA-Z0-9]".toRegex(), "_")}_${System.currentTimeMillis()}.pdf"
        val outputFile = File(context.cacheDir, fileName)
        FileOutputStream(outputFile).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()

        return outputFile
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

