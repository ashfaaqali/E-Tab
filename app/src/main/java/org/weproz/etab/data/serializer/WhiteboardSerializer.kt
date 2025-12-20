package org.weproz.etab.data.serializer

import android.graphics.Path
import com.google.gson.Gson
import org.weproz.etab.data.model.whiteboard.DrawAction
import org.weproz.etab.data.model.whiteboard.GridType
import org.weproz.etab.data.model.whiteboard.ParsedPage
import org.weproz.etab.data.model.whiteboard.ParsedWhiteboardData
import org.weproz.etab.data.model.whiteboard.WhiteboardData
import org.weproz.etab.data.model.whiteboard.SerializablePage
import org.weproz.etab.data.model.whiteboard.SerializableStroke
import org.weproz.etab.data.model.whiteboard.SerializableText

object WhiteboardSerializer {
    private val gson = Gson()

    fun serialize(pages: List<ParsedPage>): String {
        val serializablePages = pages.map { page ->
            val strokes = page.actions.filterIsInstance<DrawAction.Stroke>().map { action ->
                SerializableStroke(action.points, action.color, action.strokeWidth, action.isEraser)
            }
            val texts = page.actions.filterIsInstance<DrawAction.Text>().map { action ->
                SerializableText(action.text, action.x, action.y, action.color, action.textSize)
            }
            SerializablePage(strokes, texts, page.gridType.name)
        }
        
        // Save as multi-page V2
        val data = WhiteboardData(
            version = 2,
            pages = serializablePages
        )
        return gson.toJson(data)
    }

    fun deserialize(json: String): ParsedWhiteboardData {
        val data = try {
            gson.fromJson(json, WhiteboardData::class.java)
        } catch (e: Exception) {
            return ParsedWhiteboardData(listOf(ParsedPage(emptyList(), GridType.NONE)))
        }

        if (data == null) return ParsedWhiteboardData(listOf(ParsedPage(emptyList(), GridType.NONE)))

        val parsedPages = mutableListOf<ParsedPage>()

        if (!data.pages.isNullOrEmpty()) {
            data.pages.forEach { pageData ->
                parsedPages.add(parsePage(pageData.strokes, pageData.texts, pageData.gridType))
            }
        } else {
            // Legacy Fallback (Top level fields)
            parsedPages.add(parsePage(data.strokes, data.texts, data.gridType))
        }

        return ParsedWhiteboardData(parsedPages)
    }
    
    private fun parsePage(strokes: List<SerializableStroke>?, texts: List<SerializableText>?, gridTypeStr: String?): ParsedPage {
        val actions = mutableListOf<DrawAction>()
        
        strokes?.forEach { strokeData ->
             val path = Path()
             if (strokeData.points.isNotEmpty()) {
                 path.moveTo(strokeData.points[0].first, strokeData.points[0].second)
                 for (i in 1 until strokeData.points.size) {
                     path.lineTo(strokeData.points[i].first, strokeData.points[i].second)
                 }
             }
             actions.add(DrawAction.Stroke(path, strokeData.points, strokeData.color, strokeData.strokeWidth, strokeData.isEraser))
        }
        
        texts?.forEach { textData ->
            actions.add(DrawAction.Text(textData.text, textData.x, textData.y, textData.color, textData.textSize))
        }
        
        val gridType = try {
            GridType.valueOf(gridTypeStr ?: "NONE")
        } catch (e: Exception) {
            GridType.NONE
        }
        
        return ParsedPage(actions, gridType)
    }
}
