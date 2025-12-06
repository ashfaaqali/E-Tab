package org.weproz.etab.ui.notes.whiteboard

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs

class WhiteboardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var drawColor = Color.BLACK
    private var strokeWidth = 10f
    private var isEraser = false

    private val paths = mutableListOf<DrawAction>()
    private val undonePaths = mutableListOf<DrawAction>()

    private var currentPath = Path()
    private val currentPoints = mutableListOf<Pair<Float, Float>>()
    
    // Matrix for Zoom/Pan
    private val drawMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    
    // Panning
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isPanning = false // Two fingers to pan/zoom

    private val drawPaint = Paint().apply {
        color = drawColor
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        this.strokeWidth = strokeWidth
    }

    private val textPaint = Paint().apply {
        color = drawColor
        isAntiAlias = true
        textSize = 60f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.concat(drawMatrix) // Apply zoom/pan transformation

        for (action in paths) {
            when (action) {
                is DrawAction.Stroke -> {
                    drawPaint.color = action.color
                    drawPaint.strokeWidth = action.strokeWidth
                    canvas.drawPath(action.path, drawPaint)
                }
                is DrawAction.Text -> {
                    textPaint.color = action.color
                    textPaint.textSize = action.textSize
                    canvas.drawText(action.text, action.x, action.y, textPaint)
                }
            }
        }

        // Draw current drawing path
        if (!currentPath.isEmpty) {
            drawPaint.color = if (isEraser) Color.WHITE else drawColor
            drawPaint.strokeWidth = strokeWidth
            canvas.drawPath(currentPath, drawPaint)
        }

        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        
        // Convert touch coordinates to canvas coordinates
        val points = floatArrayOf(event.x, event.y)
        inverseMatrix.mapPoints(points)
        val canvasX = points[0]
        val canvasY = points[1]

        // Two fingers -> Pan/Zoom, no drawing
        if (event.pointerCount > 1) {
            isPanning = true
             // Handle panning
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (lastTouchX != 0f && lastTouchY != 0f) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        drawMatrix.postTranslate(dx, dy)
                        drawMatrix.invert(inverseMatrix)
                        invalidate()
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    lastTouchX = 0f 
                    lastTouchY = 0f
                }
                 MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                 }
            }
            return true
        } else {
             if (isPanning && event.actionMasked == MotionEvent.ACTION_UP) {
                 isPanning = false
                 lastTouchX = 0f
                 lastTouchY = 0f
                 return true
             }
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                undonePaths.clear()
                currentPath.reset()
                currentPath.moveTo(canvasX, canvasY)
                currentPoints.clear()
                currentPoints.add(canvasX to canvasY)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val lastPoint = currentPoints.lastOrNull()
                if (lastPoint != null) {
                     val dx = abs(canvasX - lastPoint.first)
                     val dy = abs(canvasY - lastPoint.second)
                     if (dx >= 4 || dy >= 4) {
                         currentPath.quadTo(lastPoint.first, lastPoint.second, (canvasX + lastPoint.first) / 2, (canvasY + lastPoint.second) / 2)
                         currentPoints.add(canvasX to canvasY)
                         invalidate()
                     }
                }
            }
            MotionEvent.ACTION_UP -> {
                currentPath.lineTo(canvasX, canvasY)
                val finalPath = Path(currentPath)
                val color = if (isEraser) Color.WHITE else drawColor // Simple eraser = white stroke
                // Create copy of points
                val pointsCopy = ArrayList(currentPoints)
                paths.add(DrawAction.Stroke(finalPath, pointsCopy, color, strokeWidth))
                currentPath.reset()
                invalidate()
            }
        }
        return true
    }

    fun undo() {
        if (paths.isNotEmpty()) {
            undonePaths.add(paths.removeAt(paths.lastIndex))
            invalidate()
        }
    }

    fun redo() {
        if (undonePaths.isNotEmpty()) {
            paths.add(undonePaths.removeAt(undonePaths.lastIndex))
            invalidate()
        }
    }

    fun setPenColor(color: Int) {
        isEraser = false
        drawColor = color
    }

    fun setEraser() {
        isEraser = true
        // drawColor ignored for eraser logic above
    }
    
    fun addText(text: String) {
        // Add text at center of current view
        val center = floatArrayOf(width / 2f, height / 2f)
        inverseMatrix.mapPoints(center)
        paths.add(DrawAction.Text(text, center[0], center[1], drawColor, 60f))
        invalidate()
    }
    
    // For saving/loading (Simplified for brevity, would need proper serialization)
    fun getPaths(): List<DrawAction> = paths
    
    fun loadPaths(actions: List<DrawAction>) {
        paths.clear()
        paths.addAll(actions)
        invalidate()
    }

    inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            drawMatrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
            drawMatrix.invert(inverseMatrix)
            invalidate()
            return true
        }
    }
}
