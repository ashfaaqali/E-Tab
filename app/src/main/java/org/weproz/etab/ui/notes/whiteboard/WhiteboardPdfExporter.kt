package org.weproz.etab.ui.notes.whiteboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.weproz.etab.data.model.whiteboard.ParsedPage
import java.io.File
import java.io.FileOutputStream

class WhiteboardPdfExporter(private val context: Context) {

    suspend fun exportToPdf(
        pages: List<ParsedPage>,
        width: Int,
        height: Int,
        title: String
    ): File = withContext(Dispatchers.IO) {
        val pdfDocument = PdfDocument()
        
        try {
            for (i in pages.indices) {
                val pageData = pages[i]
                
                // Generate Bitmap on Main Thread
                val bitmap = withContext(Dispatchers.Main) {
                    renderPageToBitmap(pageData, width, height)
                }
                
                val pageInfo = PdfDocument.PageInfo.Builder(width, height, i + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                pdfDocument.finishPage(page)
                
                bitmap.recycle()
            }
            
            val pdfDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val safeTitle = title.replace(" ", "_")
            val pdfFile = File(pdfDir, "$safeTitle.pdf")
            
            FileOutputStream(pdfFile).use { 
                pdfDocument.writeTo(it) 
            }
            
            return@withContext pdfFile
        } finally {
            pdfDocument.close()
        }
    }

    private fun renderPageToBitmap(pageData: ParsedPage, width: Int, height: Int): Bitmap {
        val tempView = WhiteboardView(context)
        
        tempView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        tempView.layout(0, 0, width, height)
        
        tempView.gridType = pageData.gridType
        tempView.loadPaths(pageData.actions)
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        tempView.draw(canvas)
        
        return bitmap
    }
}