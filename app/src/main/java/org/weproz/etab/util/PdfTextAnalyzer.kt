package org.weproz.etab.util

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

class PdfTextAnalyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun analyzePage(bitmap: Bitmap): Text {
        val image = InputImage.fromBitmap(bitmap, 0)
        return recognizer.process(image).await()
    }

    fun findWordAt(text: Text, x: Int, y: Int): Text.Element? {
        for (block in text.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val box = element.boundingBox
                    if (box != null && box.contains(x, y)) {
                        return element
                    }
                }
            }
        }
        return null
    }

    fun getBlocks(text: Text): List<Text.TextBlock> {
        return text.textBlocks
    }

    fun getElements(text: Text): List<Text.Element> {
        return text.textBlocks.flatMap { it.lines }.flatMap { it.elements }
    }
}
