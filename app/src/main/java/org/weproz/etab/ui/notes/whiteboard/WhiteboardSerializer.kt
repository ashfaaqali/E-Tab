package org.weproz.etab.ui.notes.whiteboard

import android.graphics.Path
import com.google.gson.Gson
import java.io.File

object WhiteboardSerializer {
    private val gson = Gson()

    fun serialize(strokes: List<DrawAction.Stroke>, texts: List<DrawAction.Text>, gridType: GridType): String {
        val serializableStrokes = strokes.map { action ->
            SerializableStroke(action.points, action.color, action.strokeWidth, action.isEraser)
        }
        val serializableTexts = texts.map { action ->
            SerializableText(action.text, action.x, action.y, action.color, action.textSize)
        }
        val data = WhiteboardData(serializableStrokes, serializableTexts, gridType.name)
        return gson.toJson(data)
    }

    fun deserialize(json: String): ParsedWhiteboardData {
        val data = gson.fromJson(json, WhiteboardData::class.java)
        
        val actions = mutableListOf<DrawAction>()
        
        // Reconstruct Strokes
        data.strokes.forEach { strokeData ->
            val path = Path()
            if (strokeData.points.isNotEmpty()) {
                path.moveTo(strokeData.points[0].first, strokeData.points[0].second)
                for (i in 1 until strokeData.points.size) {
                    path.lineTo(strokeData.points[i].first, strokeData.points[i].second)
                }
            }
            actions.add(DrawAction.Stroke(path, strokeData.points, strokeData.color, strokeData.strokeWidth, strokeData.isEraser))
        }
        
        // Reconstruct Texts
        data.texts.forEach { textData ->
            actions.add(DrawAction.Text(textData.text, textData.x, textData.y, textData.color, textData.textSize))
        }

        val gridType = try {
            GridType.valueOf(data.gridType)
        } catch (e: Exception) {
            GridType.NONE
        }

        return ParsedWhiteboardData(actions, gridType)
    }
}

data class ParsedWhiteboardData(
    val actions: List<DrawAction>,
    val gridType: GridType
)
