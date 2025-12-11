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

    var drawColor = Color.BLACK
    private var strokeWidth = 10f
    var isEraser = false
    
    fun getStrokeWidth(): Float = strokeWidth
    
    fun setStrokeWidthGeneric(width: Float) {
        strokeWidth = width
    }
    
    // Grid Setup
    var gridType: GridType = GridType.NONE
        set(value) {
            field = value
            invalidate()
        }
        
    private val gridPaint = Paint().apply {
        color = Color.LTGRAY
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    
    // Eraser Size
    fun setEraserSize(size: Float) {
        if (isEraser) {
            strokeWidth = size
        }
    }

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

    // A4 Size at approx 300 DPI
    private val PAGE_WIDTH = 2480f
    private val PAGE_HEIGHT = 3508f
    private val pageRect = RectF(0f, 0f, PAGE_WIDTH, PAGE_HEIGHT)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Center and fit page initially
        if (w > 0 && h > 0) {
            val scaleX = w.toFloat() / PAGE_WIDTH
            val scaleY = h.toFloat() / PAGE_HEIGHT
            val scale = minOf(scaleX, scaleY) * 0.9f // 90% fit
            
            val dx = (w - PAGE_WIDTH * scale) / 2f
            val dy = (h - PAGE_HEIGHT * scale) / 2f
            
            drawMatrix.reset()
            drawMatrix.postScale(scale, scale)
            drawMatrix.postTranslate(dx, dy)
            drawMatrix.invert(inverseMatrix)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw Desk Background (Outside the page)
        canvas.drawColor(Color.parseColor("#E0E0E0")) // Light Gray Desk

        canvas.save()
        canvas.concat(drawMatrix) // Apply zoom/pan transformation
        
        // Draw Page Shadow
        val shadowPaint = Paint().apply {
            color = Color.GRAY
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRect(10f, 10f, PAGE_WIDTH + 10f, PAGE_HEIGHT + 10f, shadowPaint)

        // Draw Page Background (White Paper)
        val pagePaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawRect(pageRect, pagePaint)

        // Clip to Page Bounds for content
        canvas.clipRect(pageRect)
        
        // 1. Draw Grid (Background)
        drawGrid(canvas)

        // 2. Draw Strokes (Ink Layer)
        // We use a saveLayer to composite strokes separately.
        // This ensures the ERASE mode only clears the ink, revealing the grid underneath.
        // passing null as bounds ensures it uses the entire current clip (visible area),
        // which accounts for the zoom/pan matrix applied above.
        val saveCount = canvas.saveLayer(null, null)

        for (action in paths) {
            when (action) {
                is DrawAction.Stroke -> {
                    drawPaint.strokeWidth = action.strokeWidth
                    if (action.isEraser) {
                         drawPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                         drawPaint.color = Color.TRANSPARENT // Color doesn't matter for CLEAR, but good practice
                    } else {
                         drawPaint.xfermode = null
                         drawPaint.color = action.color
                    }
                    canvas.drawPath(action.path, drawPaint)
                }
                is DrawAction.Text -> {
                    // Text is always on top logic or part of ink?
                    // If eraser should erase text, then text should be in this layer.
                    // For now, let's treat text as ink.
                    textPaint.color = action.color
                    textPaint.textSize = action.textSize
                    canvas.drawText(action.text, action.x, action.y, textPaint)
                }
            }
        }

        // Draw current drawing path
        if (!currentPath.isEmpty) {
            drawPaint.strokeWidth = strokeWidth
            if (isEraser) {
                drawPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                drawPaint.color = Color.TRANSPARENT
            } else {
                drawPaint.xfermode = null
                drawPaint.color = drawColor
            }
            canvas.drawPath(currentPath, drawPaint)
        }
        
        // Reset Paint
        drawPaint.xfermode = null
        
        canvas.restoreToCount(saveCount) // Restore layer
        canvas.restore() // Restore zoom/pan
    }

    private var movingTextAction: DrawAction.Text? = null
    private var isDraggingText = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        
        // Convert touch coordinates to canvas coordinates
        val points = floatArrayOf(event.x, event.y)
        inverseMatrix.mapPoints(points)
        
        // Clamp to Page Bounds
        val canvasX = points[0].coerceIn(0f, PAGE_WIDTH)
        val canvasY = points[1].coerceIn(0f, PAGE_HEIGHT)

        // Two fingers -> Pan/Zoom
        if (event.pointerCount > 1) {
            isPanning = true 
            // Reset text dragging if any
            if (isDraggingText) {
                isDraggingText = false
                movingTextAction = null
            }
            
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
                // Check if hitting a text item (topmost first, so reverse iteration)
                var hitText: DrawAction.Text? = null
                val bounds = Rect()
                
                for (i in paths.lastIndex downTo 0) {
                    val action = paths[i]
                    if (action is DrawAction.Text) {
                        textPaint.textSize = action.textSize
                        textPaint.getTextBounds(action.text, 0, action.text.length, bounds)
                        // Canvas drawText x,y is origin (bottom-left usually).
                        // Bounds are relative to (0,0).
                        // Actual rect:
                        val textLeft = action.x + bounds.left
                        val textTop = action.y + bounds.top
                        val textRight = action.x + bounds.right
                        val textBottom = action.y + bounds.bottom
                        
                        // Add some padding for easier touch
                        val padding = 20f
                        if (canvasX >= textLeft - padding && canvasX <= textRight + padding &&
                            canvasY >= textTop - padding && canvasY <= textBottom + padding) {
                            hitText = action
                            break
                        }
                    }
                }
                
                if (hitText != null) {
                    isDraggingText = true
                    movingTextAction = hitText
                    dragOffsetX = canvasX - hitText.x
                    dragOffsetY = canvasY - hitText.y
                } else {
                    isDraggingText = false
                    movingTextAction = null
                    
                    // Start Drawing
                    undonePaths.clear()
                    currentPath.reset()
                    currentPath.moveTo(canvasX, canvasY)
                    currentPoints.clear()
                    currentPoints.add(canvasX to canvasY)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingText && movingTextAction != null) {
                    movingTextAction!!.x = canvasX - dragOffsetX
                    movingTextAction!!.y = canvasY - dragOffsetY
                    invalidate()
                } else {
                    // Drawing
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
            }
            MotionEvent.ACTION_UP -> {
                if (isDraggingText) {
                    isDraggingText = false
                    movingTextAction = null
                } else {
                    currentPath.lineTo(canvasX, canvasY)
                    val finalPath = Path(currentPath)
                    val captureEraser = isEraser 
                    val color = if (captureEraser) Color.TRANSPARENT else drawColor 
                    // Create copy of points
                    val pointsCopy = ArrayList(currentPoints)
                    paths.add(DrawAction.Stroke(finalPath, pointsCopy, color, strokeWidth, captureEraser))
                    currentPath.reset()
                    invalidate()
                }
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
        
        val x = center[0].coerceIn(50f, PAGE_WIDTH - 50f)
        val y = center[1].coerceIn(50f, PAGE_HEIGHT - 50f)
        
        paths.add(DrawAction.Text(text, x, y, drawColor, 60f))
        invalidate()
    }
    
    // For saving/loading (Simplified for brevity, would need proper serialization)
    fun getPaths(): List<DrawAction> = paths
    
    fun loadPaths(actions: List<DrawAction>) {
        paths.clear()
        paths.addAll(actions)
        undonePaths.clear()
        currentPath.reset()
        currentPoints.clear()
        invalidate()
    }
    
    fun clear() {
        paths.clear()
        undonePaths.clear()
        currentPath.reset()
        currentPoints.clear()
        invalidate()
    }

    private fun drawGrid(canvas: Canvas) {
        if (gridType == GridType.NONE) return
        
        val gridSize = 100f // Gap
        
        val left = 0f
        val top = 0f
        val right = PAGE_WIDTH
        val bottom = PAGE_HEIGHT
        
        when (gridType) {
            GridType.DOT -> {
                gridPaint.style = Paint.Style.FILL
                var y = top + gridSize
                while (y < bottom) {
                    var x = left + gridSize
                    while (x < right) {
                        canvas.drawCircle(x, y, 4f, gridPaint)
                        x += gridSize
                    }
                    y += gridSize
                }
            }
            GridType.SQUARE -> {
                gridPaint.style = Paint.Style.STROKE
                var x = left + gridSize
                while (x < right) {
                    canvas.drawLine(x, top, x, bottom, gridPaint)
                    x += gridSize
                }
                var y = top + gridSize
                while (y < bottom) {
                    canvas.drawLine(left, y, right, y, gridPaint)
                    y += gridSize
                }
            }
            GridType.RULED -> {
                gridPaint.style = Paint.Style.STROKE
                var y = top + gridSize
                while (y < bottom) {
                    canvas.drawLine(left, y, right, y, gridPaint)
                    y += gridSize
                }
            }
            GridType.NONE -> {}
        }
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
