package org.weproz.etab.data.model.whiteboard

data class WhiteboardData(
    val version: Int,
    val pages: List<SerializablePage>?,
    // Legacy fields
    val strokes: List<SerializableStroke>? = null,
    val texts: List<SerializableText>? = null,
    val gridType: String? = null
)

data class SerializablePage(
    val strokes: List<SerializableStroke>,
    val texts: List<SerializableText>,
    val gridType: String
)

data class SerializableStroke(
    val points: List<Pair<Float, Float>>,
    val color: Int,
    val strokeWidth: Float,
    val isEraser: Boolean
)

data class SerializableText(
    val text: String,
    val x: Float,
    val y: Float,
    val color: Int,
    val textSize: Float
)
