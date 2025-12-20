package org.weproz.etab.data.model.whiteboard

import android.graphics.Path
import android.graphics.PointF

sealed class DrawAction {
    data class Stroke(val path: Path, var points: List<Pair<Float, Float>>, val color: Int, val strokeWidth: Float, val isEraser: Boolean = false) : DrawAction()
    data class Text(val text: String, var x: Float, var y: Float, val color: Int, var textSize: Float) : DrawAction()
    // Eraser can be implemented as a Stroke with background color or PorterDuff mode, 
    // but for undo/redo simplicity in this custom view, we might just use Stroke with specific properties.
    // Or we can have a specific Eraser action that "cuts" holes, but simple white stroke is easier for MVP.
}
