package org.weproz.etab.ui.notes.whiteboard

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import org.weproz.etab.data.model.whiteboard.DrawAction
import org.weproz.etab.data.model.whiteboard.GridType
import org.weproz.etab.R
import kotlin.math.abs

class WhiteboardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class ToolType {
        PEN, ERASER, SELECTOR
    }

    var currentTool: ToolType = ToolType.PEN
        set(value) {
            field = value
            // Clear selection when switching tools
            if (value != ToolType.SELECTOR) {
                selectedTextAction = null
                invalidate()
            }
        }

    // Deprecate isEraser in favor of currentTool
    var isEraser: Boolean
        get() = currentTool == ToolType.ERASER
        set(value) {
            currentTool = if (value) ToolType.ERASER else ToolType.PEN
        }

    private var selectedTextAction: DrawAction.Text? = null
    private val selectionPaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    private val handlePaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.FILL
    }
    private val removeBtnPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
    }
    private val removeBtnTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 30f
        textAlign = Paint.Align.CENTER
    }

    var drawColor = Color.BLACK
    var isHighlighter = false
    var isTransparentBackground = false
    
    // Clipping bounds for PDF annotation
    private var clipBoundsRect: RectF? = null
    
    fun setClipBounds(rect: RectF?) {
        clipBoundsRect = rect
        invalidate()
    }
    
    private val effectiveDrawColor: Int
        get() {
            return if (isHighlighter) {
                Color.argb(80, Color.red(drawColor), Color.green(drawColor), Color.blue(drawColor))
            } else {
                drawColor
            }
        }

    private var penStrokeWidth = 10f
    private var eraserStrokeWidth = 30f

    private val currentStrokeWidth: Float
        get() = if (currentTool == ToolType.ERASER) eraserStrokeWidth else penStrokeWidth
    
    var onActionCompleted: (() -> Unit)? = null
    
    fun getStrokeWidth(): Float = currentStrokeWidth
    
    fun setStrokeWidthGeneric(width: Float) {
        if (currentTool == ToolType.ERASER) {
            eraserStrokeWidth = width
        } else {
            penStrokeWidth = width
        }
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
        eraserStrokeWidth = size
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
        this.strokeWidth = currentStrokeWidth
    }

    private val textPaint = Paint().apply {
        color = drawColor
        isAntiAlias = true
        textSize = 60f
    }

    // Page Size (Dynamic based on View size)
    private var PAGE_WIDTH = 0f
    private var PAGE_HEIGHT = 0f
    private val pageRect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            // Set page size to screen size
            PAGE_WIDTH = w.toFloat()
            PAGE_HEIGHT = h.toFloat()
            pageRect.set(0f, 0f, PAGE_WIDTH, PAGE_HEIGHT)
            
            // Reset matrix (1:1 scale initially)
            drawMatrix.reset()
            drawMatrix.invert(inverseMatrix)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (!isTransparentBackground) {
            // Draw Desk Background (Outside the page)
            canvas.drawColor(context.getColor(R.color.md_theme_error))
        }

        canvas.save()
        canvas.concat(drawMatrix) // Apply zoom/pan transformation
        
        if (!isTransparentBackground) {
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
        }

        // Clip to Page Bounds for content
        if (isTransparentBackground) {
            clipBoundsRect?.let { rect ->
                canvas.clipRect(rect)
            }
        } else {
            canvas.clipRect(pageRect)
        }
        
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
            drawPaint.strokeWidth = currentStrokeWidth
            if (currentTool == ToolType.ERASER) {
                drawPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                drawPaint.color = Color.TRANSPARENT
            } else {
                drawPaint.xfermode = null
                drawPaint.color = effectiveDrawColor
            }
            canvas.drawPath(currentPath, drawPaint)
        }
        
        // Reset Paint
        drawPaint.xfermode = null
        
        // Draw Selection Box for Text
        selectedTextAction?.let { textAction ->
            drawSelectionBox(canvas, textAction)
        }
        
        // Draw Lasso
        if (isLassoSelecting) {
            canvas.drawPath(lassoPath, lassoPaint)
        }
        
        // Draw Selection Bounds
        selectionBounds?.let { rect ->
            canvas.drawRect(rect, selectionPaint)
        }
        
        canvas.restoreToCount(saveCount) // Restore layer
        canvas.restore() // Restore zoom/pan
    }

    private fun drawSelectionBox(canvas: Canvas, action: DrawAction.Text) {
        val bounds = getTextBounds(action)
        val padding = 20f
        val rect = RectF(
            bounds.left - padding,
            bounds.top - padding,
            bounds.right + padding,
            bounds.bottom + padding
        )
        
        // Draw dashed border
        canvas.drawRect(rect, selectionPaint)
        
        // Draw Remove Button (Top-Right)
        val removeBtnRadius = 25f
        canvas.drawCircle(rect.right, rect.top, removeBtnRadius, removeBtnPaint)
        // Draw 'X'
        val fontMetrics = removeBtnTextPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val textOffset = (textHeight / 2) - fontMetrics.descent
        canvas.drawText("X", rect.right, rect.top + textOffset, removeBtnTextPaint)
        
        // Draw Resize Handle (Bottom-Right)
        val handleRadius = 20f
        canvas.drawCircle(rect.right, rect.bottom, handleRadius, handlePaint)
    }

    private fun getTextBounds(action: DrawAction.Text): RectF {
        textPaint.textSize = action.textSize
        val bounds = Rect()
        textPaint.getTextBounds(action.text, 0, action.text.length, bounds)
        // textPaint.getTextBounds returns minimal bounding box relative to (0,0)
        // drawText draws at (x,y) which is the baseline origin.
        // We need to adjust.
        // Actually, getTextBounds returns rect relative to origin (0,0).
        // So if we draw at (x,y), the rect is (x+left, y+top, x+right, y+bottom).
        return RectF(
            action.x + bounds.left,
            action.y + bounds.top,
            action.x + bounds.right,
            action.y + bounds.bottom
        )
    }

    private var movingTextAction: DrawAction.Text? = null
    private var isDraggingText = false
    private var isResizingText = false
    private var initialTextSize = 0f
    private var initialTouchY = 0f
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    // Lasso
    private val lassoPath = Path()
    private val lassoPaint = Paint().apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }
    private var isLassoSelecting = false
    private val selectedStrokes = mutableListOf<DrawAction.Stroke>()
    private var selectionBounds: RectF? = null
    private var isDraggingSelection = false
    private var lastDragX = 0f
    private var lastDragY = 0f

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
            isResizingText = false
            isLassoSelecting = false
            isDraggingSelection = false
            
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
                if (currentTool == ToolType.SELECTOR) {
                    // Check if inside existing selection
                    if (selectionBounds != null && selectionBounds!!.contains(canvasX, canvasY)) {
                        isDraggingSelection = true
                        lastDragX = canvasX
                        lastDragY = canvasY
                        return true
                    }
                    
                    // Start new Lasso
                    selectedStrokes.clear()
                    selectionBounds = null
                    selectedTextAction = null // Clear text selection too
                    isLassoSelecting = true
                    lassoPath.reset()
                    lassoPath.moveTo(canvasX, canvasY)
                    invalidate()
                    return true
                }

                // 1. Check Selection Handles/Body (Priority)
                val currentSelection = selectedTextAction
                if (currentSelection != null) {
                    val bounds = getTextBounds(currentSelection)
                    val padding = 20f
                    val rect = RectF(bounds.left - padding, bounds.top - padding, bounds.right + padding, bounds.bottom + padding)
                    
                    // Remove
                    if (dist(canvasX, canvasY, rect.right, rect.top) <= 40f) {
                        paths.remove(currentSelection)
                        selectedTextAction = null
                        invalidate()
                        return true
                    }
                    
                    // Resize
                    if (dist(canvasX, canvasY, rect.right, rect.bottom) <= 40f) {
                        isResizingText = true
                        initialTextSize = currentSelection.textSize
                        initialTouchY = canvasY
                        return true
                    }
                    
                    // Move
                    if (rect.contains(canvasX, canvasY)) {
                        isDraggingText = true
                        movingTextAction = currentSelection
                        dragOffsetX = canvasX - currentSelection.x
                        dragOffsetY = canvasY - currentSelection.y
                        return true
                    }
                    
                    // Tapped outside selection -> Deselect
                    selectedTextAction = null
                    invalidate()
                }
                
                // 2. Check if hitting NEW text (to select)
                var hitText: DrawAction.Text? = null
                for (i in paths.lastIndex downTo 0) {
                    val action = paths[i]
                    if (action is DrawAction.Text) {
                        val bounds = getTextBounds(action)
                        val padding = 20f
                        if (canvasX >= bounds.left - padding && canvasX <= bounds.right + padding &&
                            canvasY >= bounds.top - padding && canvasY <= bounds.bottom + padding) {
                            hitText = action
                            break
                        }
                    }
                }
                
                if (hitText != null) {
                    selectedTextAction = hitText
                    isDraggingText = true
                    movingTextAction = hitText
                    dragOffsetX = canvasX - hitText.x
                    dragOffsetY = canvasY - hitText.y
                    invalidate()
                    return true
                }
                
                // 3. Drawing (if not handled by text)
                if (currentTool == ToolType.PEN || currentTool == ToolType.ERASER) {
                    undonePaths.clear()
                    currentPath.reset()
                    currentPath.moveTo(canvasX, canvasY)
                    currentPoints.clear()
                    currentPoints.add(canvasX to canvasY)
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingSelection) {
                    val dx = canvasX - lastDragX
                    val dy = canvasY - lastDragY
                    
                    // Move strokes
                    for (stroke in selectedStrokes) {
                        stroke.path.offset(dx, dy)
                        val newPoints = stroke.points.map { it.first + dx to it.second + dy }
                        stroke.points = newPoints
                    }
                    
                    // Update bounds
                    selectionBounds?.offset(dx, dy)
                    
                    lastDragX = canvasX
                    lastDragY = canvasY
                    invalidate()
                } else if (isLassoSelecting) {
                    lassoPath.lineTo(canvasX, canvasY)
                    invalidate()
                } else if (isResizingText && selectedTextAction != null) {
                    val dy = canvasY - initialTouchY
                    val newSize = (initialTextSize + dy / 2).coerceIn(20f, 300f)
                    selectedTextAction!!.textSize = newSize
                    invalidate()
                } else if (isDraggingText && movingTextAction != null) {
                    movingTextAction!!.x = canvasX - dragOffsetX
                    movingTextAction!!.y = canvasY - dragOffsetY
                    invalidate()
                } else if (currentTool == ToolType.PEN || currentTool == ToolType.ERASER) {
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
                if (isLassoSelecting) {
                    isLassoSelecting = false
                    lassoPath.close()
                    findSelectedStrokes()
                    lassoPath.reset()
                    invalidate()
                } else if (isDraggingSelection) {
                    isDraggingSelection = false
                    onActionCompleted?.invoke()
                }
                
                isDraggingText = false
                isResizingText = false
                movingTextAction = null
                
                if (currentTool == ToolType.PEN || currentTool == ToolType.ERASER) {
                    if (currentPoints.isNotEmpty()) {
                        currentPath.lineTo(canvasX, canvasY)
                        // Commit path
                        val stroke = DrawAction.Stroke(
                            Path(currentPath),
                            ArrayList(currentPoints),
                            if (currentTool == ToolType.ERASER) Color.TRANSPARENT else effectiveDrawColor,
                            currentStrokeWidth,
                            currentTool == ToolType.ERASER
                        )
                        paths.add(stroke)
                        currentPath.reset()
                        currentPoints.clear()
                        onActionCompleted?.invoke()
                        invalidate()
                    }
                }
            }
        }
        return true
    }
    
    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return Math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()
    }

    fun undo() {
        if (paths.isNotEmpty()) {
            undonePaths.add(paths.removeAt(paths.lastIndex))
            invalidate()
            onActionCompleted?.invoke()
        }
    }

    fun redo() {
        if (undonePaths.isNotEmpty()) {
            paths.add(undonePaths.removeAt(undonePaths.lastIndex))
            invalidate()
            onActionCompleted?.invoke()
        }
    }

    fun setPenColor(color: Int) {
        isEraser = false
        drawColor = color
    }

    fun setEraser() {
        currentTool = ToolType.ERASER
    }
    
    fun setTool(tool: ToolType) {
        currentTool = tool
    }
    
    fun addText(text: String) {
        // Add text at center of current view
        val center = floatArrayOf(width / 2f, height / 2f)
        inverseMatrix.mapPoints(center)
        
        val x = center[0].coerceIn(50f, PAGE_WIDTH - 50f)
        val y = center[1].coerceIn(50f, PAGE_HEIGHT - 50f)
        
        val newText = DrawAction.Text(text, x, y, drawColor, 60f)
        paths.add(newText)
        selectedTextAction = newText // Auto-select
        invalidate()
        onActionCompleted?.invoke()
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

    private fun findSelectedStrokes() {
        selectedStrokes.clear()
        val lassoRegion = Region()
        val rectF = RectF()
        lassoPath.computeBounds(rectF, true)
        lassoRegion.setPath(lassoPath, Region(rectF.left.toInt(), rectF.top.toInt(), rectF.right.toInt(), rectF.bottom.toInt()))
        
        for (action in paths) {
            if (action is DrawAction.Stroke) {
                // Check if any point of the stroke is inside the lasso
                // Optimization: Check bounds first
                val strokeBounds = RectF()
                action.path.computeBounds(strokeBounds, true)
                
                if (RectF.intersects(rectF, strokeBounds)) {
                    // Detailed check
                    var isInside = false
                    for (point in action.points) {
                        if (lassoRegion.contains(point.first.toInt(), point.second.toInt())) {
                            isInside = true
                            break
                        }
                    }
                    
                    if (isInside) {
                        selectedStrokes.add(action)
                    }
                }
            }
        }
        
        if (selectedStrokes.isNotEmpty()) {
            calculateSelectionBounds()
        } else {
            selectionBounds = null
        }
    }

    private fun calculateSelectionBounds() {
        if (selectedStrokes.isEmpty()) {
            selectionBounds = null
            return
        }
        
        val bounds = RectF()
        bounds.set(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        
        for (stroke in selectedStrokes) {
            val strokeBounds = RectF()
            stroke.path.computeBounds(strokeBounds, true)
            bounds.union(strokeBounds)
        }
        
        // Add padding
        bounds.inset(-20f, -20f)
        selectionBounds = bounds
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
