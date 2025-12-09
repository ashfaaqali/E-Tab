package org.weproz.etab.ui.notes.whiteboard

import android.graphics.Path
import android.graphics.PointF

sealed class DrawAction {
    data class Stroke(val path: Path, val points: List<Pair<Float, Float>>, val color: Int, val strokeWidth: Float, val isEraser: Boolean = false) : DrawAction()
    data class Text(val text: String, var x: Float, var y: Float, val color: Int, val textSize: Float) : DrawAction()
    // Eraser can be implemented as a Stroke with background color or PorterDuff mode, 
    // but for undo/redo simplicity in this custom view, we might just use Stroke with specific properties.
    // Or we can have a specific Eraser action that "cuts" holes, but simple white stroke is easier for MVP.
}

// Serializable version for saving to JSON
data class SerializableStroke(
    val points: List<Pair<Float, Float>>,
    val color: Int,
    val strokeWidth: Float,
    val isEraser: Boolean = false
)

data class SerializableText(
    val text: String,
    val x: Float, 
    val y: Float,
    val color: Int, 
    val textSize: Float
)

data class SerializablePage(
    val strokes: List<SerializableStroke> = emptyList(),
    val texts: List<SerializableText> = emptyList(),
    val gridType: String = "NONE"
)

data class WhiteboardData(
    // Legacy support (optional if pages list is empty)
    val strokes: List<SerializableStroke> = emptyList(),
    val texts: List<SerializableText> = emptyList(),
    val gridType: String = "NONE",
    
    // Multi-page support
    val version: Int = 1,
    val pages: List<SerializablePage> = emptyList()
)
