package org.weproz.etab.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.weproz.etab.data.local.HighlightEntity
import org.weproz.etab.util.PdfTextAnalyzer
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * A high-performance PDF viewer view that renders PDF pages with support for:
 * - Pinch-to-zoom
 * - Pan/scroll gestures
 * - Fling scrolling
 * - Individual page rendering with gaps (Google Drive-like experience)
 * - Efficient memory management with page recycling
 */
class PdfViewerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "PdfViewerView"
        private const val MIN_SCALE = 0.5f
        private const val MAX_SCALE = 5.0f
        private const val DEFAULT_SCALE = 1.0f
        private const val PAGE_GAP_DP = 16  // Gap between pages in dp
        private const val PAGE_SHADOW_RADIUS_DP = 4f
        private const val MAX_CACHED_PAGES = 5  // Maximum number of pages to keep in memory
    }

    // Text Analysis
    private val textAnalyzer = PdfTextAnalyzer()
    private val highlightPaint = Paint().apply {
        color = Color.YELLOW
        alpha = 100
        style = Paint.Style.FILL
    }
    private val selectionPaint = Paint().apply {
        color = Color.parseColor("#4D2196F3") // Light Blue with alpha
        style = Paint.Style.FILL
    }
    private val handlePaint = Paint().apply {
        color = Color.parseColor("#2196F3") // Blue
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // Selection State
    private var isSelecting = false
    private var selectionStartPage = -1
    private var selectionStartIndex = -1
    private var selectionEndIndex = -1
    private var selectionRects = listOf<RectF>()
    private var draggingHandle: HandleType? = null
    private val handleRadius = 30f
    
    private enum class HandleType { START, END }
    
    private data class PageTextData(
        val elements: List<com.google.mlkit.vision.text.Text.Element>,
        val width: Int,
        val height: Int
    )
    
    // Cache for text analysis results
    private val pageTextCache = mutableMapOf<Int, PageTextData>()

    // Highlights
    private var highlights = listOf<HighlightEntity>()
    var onHighlightActionListener: OnHighlightActionListener? = null

    interface OnHighlightActionListener {
        fun onWordSelected(text: String, selectionRects: List<RectF>, screenRect: RectF, pageIndex: Int)
        fun onHighlightClicked(highlight: HighlightEntity, rect: RectF)
    }

    // PDF Renderer
    private var pdfRenderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pageCount: Int = 0

    // Page cache for efficient rendering
    private data class CachedPage(
        val pageIndex: Int,
        val bitmap: Bitmap,
        val renderScale: Float
    )
    private val pageCache = mutableMapOf<Int, CachedPage>()

    // View transformation
    private var currentScale = DEFAULT_SCALE
    private var translateX = 0f
    private var translateY = 0f

    // Page dimensions (original PDF dimensions)
    private var originalPageWidth = 0
    private var originalPageHeight = 0

    // Display dimensions (scaled to fit view)
    private var pageWidth = 0
    private var pageHeight = 0
    private var pageGapPx: Int = 0
    private var totalContentHeight = 0f
    private var dimensionsCalculated = false

    // Touch handling
    private val scaleGestureDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector
    private val scroller: OverScroller
    private var isScaling = false

    // Drawing
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33000000")
        setShadowLayer(
            PAGE_SHADOW_RADIUS_DP * resources.displayMetrics.density,
            0f,
            2 * resources.displayMetrics.density,
            Color.parseColor("#44000000")
        )
    }
    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#F5F5F5")  // Light gray background
    }
    private val pageBgPaint = Paint().apply {
        color = Color.WHITE
    }

    // Listener for page changes
    var onPageChangeListener: ((currentPage: Int, totalPages: Int) -> Unit)? = null
    private var currentVisiblePage = 0
    
    // Listener for tap events (used to show/hide controls)
    var onTapListener: (() -> Unit)? = null

    fun setHighlights(newHighlights: List<HighlightEntity>) {
        highlights = newHighlights
        invalidate()
    }

    init {
        pageGapPx = (PAGE_GAP_DP * resources.displayMetrics.density).toInt()
        
        // Enable hardware layer for smooth rendering
        setLayerType(LAYER_TYPE_HARDWARE, null)
        
        scroller = OverScroller(context)
        
        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var focusX = 0f
            private var focusY = 0f
            
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                focusX = detector.focusX
                focusY = detector.focusY
                return true
            }
            
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val newScale = (currentScale * scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                
                if (newScale != currentScale) {
                    // Scale around the focus point
                    val focusContentX = (focusX - translateX) / currentScale
                    val focusContentY = (focusY - translateY) / currentScale
                    
                    currentScale = newScale
                    
                    translateX = focusX - focusContentX * currentScale
                    translateY = focusY - focusContentY * currentScale
                    
                    constrainTranslation()
                    invalidate()
                }
                return true
            }
            
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
                // Re-render pages at new scale if needed
                renderVisiblePages()
            }
        })
        
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (!isScaling) {
                    translateX -= distanceX
                    translateY -= distanceY
                    constrainTranslation()
                    updateCurrentPage()
                    invalidate()
                }
                return true
            }
            
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (!isScaling) {
                    // Content width includes page + gaps on both sides
                    val contentWidth = (pageWidth + 2 * pageGapPx) * currentScale
                    val contentHeight = totalContentHeight * currentScale
                    
                    val minX = min(0f, width - contentWidth)
                    val maxX = max(0f, 0f)
                    val minY = min(0f, height - contentHeight)
                    val maxY = max(0f, 0f)
                    
                    scroller.fling(
                        translateX.toInt(),
                        translateY.toInt(),
                        velocityX.toInt(),
                        velocityY.toInt(),
                        minX.toInt(),
                        maxX.toInt(),
                        minY.toInt(),
                        maxY.toInt()
                    )
                    postInvalidateOnAnimation()
                }
                return true
            }
            
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Toggle between default scale and 2x zoom
                val targetScale = if (currentScale > 1.5f) DEFAULT_SCALE else 2.0f
                animateScaleTo(targetScale, e.x, e.y)
                return true
            }
            
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Check if tapped on a highlight
                val (pageIndex, pageX, pageY) = getPageCoordinates(e.x, e.y)
                
                // If selecting, clear selection on tap outside
                if (isSelecting) {
                    clearSelection()
                    return true
                }

                if (pageIndex != -1) {
                    val clickedHighlight = highlights.find { 
                        if (it.chapterIndex != pageIndex) return@find false
                        try {
                            val coords = it.rangeData.split(",").map { c -> c.toFloat() }
                            if (coords.size == 4) {
                                val rect = RectF(coords[0], coords[1], coords[2], coords[3])
                                // Normalize tap coordinates to 0..1
                                val normX = pageX / pageWidth
                                val normY = pageY / pageHeight
                                rect.contains(normX, normY)
                            } else false
                        } catch (ex: Exception) { false }
                    }
                    
                    if (clickedHighlight != null) {
                        // Calculate screen rect for the highlight
                        val coords = clickedHighlight.rangeData.split(",").map { it.toFloat() }
                        val pageTop = pageGapPx + pageIndex * (pageHeight + pageGapPx)
                        val screenRect = RectF(
                            (translateX + (pageGapPx + coords[0] * pageWidth) * currentScale),
                            (translateY + (pageTop + coords[1] * pageHeight) * currentScale),
                            (translateX + (pageGapPx + coords[2] * pageWidth) * currentScale),
                            (translateY + (pageTop + coords[3] * pageHeight) * currentScale)
                        )
                        onHighlightActionListener?.onHighlightClicked(clickedHighlight, screenRect)
                        return true
                    }
                }

                // Notify listener for showing controls
                onTapListener?.invoke()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val (pageIndex, pageX, pageY) = getPageCoordinates(e.x, e.y)
                if (pageIndex != -1) {
                    val cached = pageCache[pageIndex]
                    if (cached != null) {
                        CoroutineScope(Dispatchers.Main).launch {
                            try {
                                val text = withContext(Dispatchers.Default) {
                                    textAnalyzer.analyzePage(cached.bitmap)
                                }
                                
                                // Cache elements
                                val elements = textAnalyzer.getElements(text)
                                val bW = cached.bitmap.width
                                val bH = cached.bitmap.height
                                pageTextCache[pageIndex] = PageTextData(elements, bW, bH)
                                
                                // Find word at touch
                                val touchX = (pageX / pageWidth) * bW
                                val touchY = (pageY / pageHeight) * bH
                                
                                var foundIndex = -1
                                for (i in elements.indices) {
                                    val box = elements[i].boundingBox
                                    if (box != null && box.contains(touchX.toInt(), touchY.toInt())) {
                                        foundIndex = i
                                        break
                                    }
                                }
                                
                                if (foundIndex != -1) {
                                    isSelecting = true
                                    selectionStartPage = pageIndex
                                    selectionStartIndex = foundIndex
                                    selectionEndIndex = foundIndex
                                    updateSelectionRects()
                                    invalidate()
                                    
                                    // Show menu immediately for single word
                                    showSelectionMenu()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error analyzing text", e)
                            }
                        }
                    }
                }
            }
        })
    }

    private fun getPageCoordinates(screenX: Float, screenY: Float): Triple<Int, Float, Float> {
        val contentX = (screenX - translateX) / currentScale
        val contentY = (screenY - translateY) / currentScale
        
        val pageHeightWithGap = pageHeight + pageGapPx
        val pageIndex = ((contentY - pageGapPx / 2) / pageHeightWithGap).toInt()
        
        if (pageIndex in 0 until pageCount) {
            val pageTop = pageGapPx + pageIndex * pageHeightWithGap
            val pageY = contentY - pageTop
            val pageX = contentX - pageGapPx
            
            if (pageX >= 0 && pageX <= pageWidth && pageY >= 0 && pageY <= pageHeight) {
                return Triple(pageIndex, pageX, pageY)
            }
        }
        return Triple(-1, 0f, 0f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Handle selection dragging
        if (isSelecting && selectionRects.isNotEmpty()) {
            val (pageIndex, pageX, pageY) = getPageCoordinates(event.x, event.y)
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Check if touching handles
                    val first = selectionRects.first()
                    val last = selectionRects.last()
                    
                    val startX = (translateX + (pageGapPx + first.left * pageWidth) * currentScale)
                    val startY = (translateY + (pageGapPx + selectionStartPage * (pageHeight + pageGapPx) + first.bottom * pageHeight) * currentScale)
                    val endX = (translateX + (pageGapPx + last.right * pageWidth) * currentScale)
                    val endY = (translateY + (pageGapPx + selectionStartPage * (pageHeight + pageGapPx) + last.bottom * pageHeight) * currentScale)
                    
                    val touchRadius = handleRadius * 2 // Larger touch area
                    
                    if (kotlin.math.hypot(event.x - startX, event.y - startY) < touchRadius) {
                        draggingHandle = HandleType.START
                        return true
                    } else if (kotlin.math.hypot(event.x - endX, event.y - endY) < touchRadius) {
                        draggingHandle = HandleType.END
                        return true
                    }
                    // Fall through to allow scrolling/tapping
                }
                MotionEvent.ACTION_MOVE -> {
                    if (draggingHandle != null) {
                        if (pageIndex == selectionStartPage) {
                            updateSelection(pageX, pageY, draggingHandle!!)
                        }
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (draggingHandle != null) {
                        draggingHandle = null
                        showSelectionMenu()
                        return true
                    }
                }
            }
        }

        var handled = scaleGestureDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled
        return handled || super.onTouchEvent(event)
    }

    private fun updateSelection(pageX: Float, pageY: Float, handle: HandleType) {
        val data = pageTextCache[selectionStartPage] ?: return
        val elements = data.elements
        
        // Find closest element to touch point
        var closestIndex = -1
        var minDist = Float.MAX_VALUE
        
        // Normalize coordinates
        val normX = pageX / pageWidth
        val normY = pageY / pageHeight
        
        val bW = data.width.toFloat()
        val bH = data.height.toFloat()
        
        elements.forEachIndexed { index, element ->
            val box = element.boundingBox ?: return@forEachIndexed
            
            val eCx = box.centerX() / bW
            val eCy = box.centerY() / bH
            
            val dist = kotlin.math.hypot(normX - eCx, normY - eCy)
            if (dist < minDist) {
                minDist = dist
                closestIndex = index
            }
        }
        
        if (closestIndex != -1) {
            if (handle == HandleType.START) {
                if (closestIndex <= selectionEndIndex) {
                    selectionStartIndex = closestIndex
                }
            } else {
                if (closestIndex >= selectionStartIndex) {
                    selectionEndIndex = closestIndex
                }
            }
            updateSelectionRects()
            invalidate()
        }
    }

    private fun updateSelectionRects() {
        val data = pageTextCache[selectionStartPage] ?: return
        val elements = data.elements
        if (selectionStartIndex == -1 || selectionEndIndex == -1) return
        
        val bW = data.width.toFloat()
        val bH = data.height.toFloat()
        
        val newRects = mutableListOf<RectF>()
        
        for (i in selectionStartIndex..selectionEndIndex) {
            val box = elements[i].boundingBox ?: continue
            newRects.add(RectF(
                box.left / bW,
                box.top / bH,
                box.right / bW,
                box.bottom / bH
            ))
        }
        selectionRects = newRects
    }

    private fun showSelectionMenu() {
        val data = pageTextCache[selectionStartPage] ?: return
        val elements = data.elements
        val sb = StringBuilder()
        for (i in selectionStartIndex..selectionEndIndex) {
            sb.append(elements[i].text).append(" ")
        }
        val text = sb.toString().trim()
        
        // Calculate bounding rect of selection for menu position
        if (selectionRects.isEmpty()) return
        
        // Union of all rects
        var minLeft = Float.MAX_VALUE
        var minTop = Float.MAX_VALUE
        var maxRight = Float.MIN_VALUE
        var maxBottom = Float.MIN_VALUE
        
        selectionRects.forEach { 
            minLeft = min(minLeft, it.left)
            minTop = min(minTop, it.top)
            maxRight = max(maxRight, it.right)
            maxBottom = max(maxBottom, it.bottom)
        }
        
        val rect = RectF(minLeft, minTop, maxRight, maxBottom)
        
        // Convert to screen coordinates for the listener
        val pageTop = pageGapPx + selectionStartPage * (pageHeight + pageGapPx)
        val screenRect = RectF(
            (translateX + (pageGapPx + rect.left * pageWidth) * currentScale),
            (translateY + (pageTop + rect.top * pageHeight) * currentScale),
            (translateX + (pageGapPx + rect.right * pageWidth) * currentScale),
            (translateY + (pageTop + rect.bottom * pageHeight) * currentScale)
        )
        
        onHighlightActionListener?.onWordSelected(text, selectionRects, screenRect, selectionStartPage)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            translateX = scroller.currX.toFloat()
            translateY = scroller.currY.toFloat()
            constrainTranslation()
            updateCurrentPage()
            postInvalidateOnAnimation()
        }
    }

    private fun constrainTranslation() {
        // Content width includes page + gaps on both sides
        val contentWidth = (pageWidth + 2 * pageGapPx) * currentScale
        val contentHeight = totalContentHeight * currentScale
        
        // Horizontal constraints
        when {
            contentWidth <= width -> {
                // Center content if smaller than view
                translateX = (width - contentWidth) / 2
            }
            else -> {
                translateX = translateX.coerceIn(width - contentWidth, 0f)
            }
        }
        
        // Vertical constraints
        when {
            contentHeight <= height -> {
                // Center content if smaller than view
                translateY = (height - contentHeight) / 2
            }
            else -> {
                translateY = translateY.coerceIn(height - contentHeight, 0f)
            }
        }
    }

    private fun updateCurrentPage() {
        if (pageHeight == 0 || pageCount == 0) return
        
        val scrollY = -translateY / currentScale
        val pageHeightWithGap = pageHeight + pageGapPx
        val page = (scrollY / pageHeightWithGap).toInt().coerceIn(0, pageCount - 1)
        
        if (page != currentVisiblePage) {
            currentVisiblePage = page
            onPageChangeListener?.invoke(currentVisiblePage + 1, pageCount)
        }
        
        renderVisiblePages()
    }

    private fun animateScaleTo(targetScale: Float, focusX: Float, focusY: Float) {
        val startScale = currentScale
        val startTransX = translateX
        val startTransY = translateY
        
        val focusContentX = (focusX - startTransX) / startScale
        val focusContentY = (focusY - startTransY) / startScale
        
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                currentScale = startScale + (targetScale - startScale) * fraction
                translateX = focusX - focusContentX * currentScale
                translateY = focusY - focusContentY * currentScale
                constrainTranslation()
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    renderVisiblePages()
                }
            })
            start()
        }
    }

    /**
     * Opens a PDF file for viewing
     */
    fun openPdf(file: File) {
        closePdf()
        
        try {
            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(fileDescriptor!!)
            pageCount = pdfRenderer!!.pageCount
            
            Log.d(TAG, "Opened PDF with $pageCount pages")
            
            // Get dimensions from first page
            if (pageCount > 0) {
                val page = pdfRenderer!!.openPage(0)
                originalPageWidth = page.width
                originalPageHeight = page.height
                page.close()
                
                Log.d(TAG, "Original page dimensions: ${originalPageWidth}x${originalPageHeight}")
            }
            
            // Reset view state
            currentScale = DEFAULT_SCALE
            translateX = 0f
            translateY = 0f
            currentVisiblePage = 0
            dimensionsCalculated = false

            // If view already has size, calculate dimensions and render immediately
            if (width > 0 && height > 0) {
                calculateDimensions()
                renderVisiblePages()
            } else {
                // Request layout to calculate proper dimensions
                requestLayout()
            }

            invalidate()
        } catch (e: Exception) {
            Log.e(TAG, "Error opening PDF", e)
        }
    }

    /**
     * Closes the current PDF and releases resources
     */
    fun closePdf() {
        // Clear cache
        pageCache.values.forEach { it.bitmap.recycle() }
        pageCache.clear()
        pageTextCache.clear()
        
        pdfRenderer?.close()
        pdfRenderer = null
        
        fileDescriptor?.close()
        fileDescriptor = null
        
        pageCount = 0
        originalPageWidth = 0
        originalPageHeight = 0
        pageWidth = 0
        pageHeight = 0
        dimensionsCalculated = false
        
        clearSelection()
    }

    fun clearSelection() {
        isSelecting = false
        selectionStartPage = -1
        selectionStartIndex = -1
        selectionEndIndex = -1
        selectionRects = emptyList()
        draggingHandle = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (pdfRenderer != null && originalPageWidth > 0) {
            dimensionsCalculated = false
            calculateDimensions()
            renderVisiblePages()
        }
    }

    private fun calculateDimensions() {
        if (originalPageWidth == 0 || originalPageHeight == 0) return
        if (width == 0 || height == 0) return

        // Calculate scale to fit page width with margins
        val horizontalPadding = 2 * pageGapPx
        val availableWidth = width - horizontalPadding
        val fitScale = availableWidth.toFloat() / originalPageWidth

        // Calculate display dimensions (scaled to fit view)
        pageWidth = (originalPageWidth * fitScale).toInt()
        pageHeight = (originalPageHeight * fitScale).toInt()

        // Calculate total content height (pages + gaps)
        totalContentHeight = (pageHeight * pageCount + pageGapPx * (pageCount + 1)).toFloat()
        
        // Content width is the page width plus the gap on each side
        val contentWidth = pageWidth + horizontalPadding
        
        // Center content horizontally
        translateX = (width - contentWidth * currentScale) / 2
        
        // Start from top
        translateY = 0f

        dimensionsCalculated = true

        Log.d(TAG, "Calculated dimensions: pageWidth=$pageWidth, pageHeight=$pageHeight, totalHeight=$totalContentHeight, contentWidth=$contentWidth")
        
        // Notify listener
        onPageChangeListener?.invoke(1, pageCount)
    }

    private fun renderVisiblePages() {
        if (pdfRenderer == null || pageHeight == 0) return
        
        // Calculate visible page range
        val scrollY = -translateY / currentScale
        val visibleTop = scrollY
        val visibleBottom = scrollY + height / currentScale
        
        val pageHeightWithGap = pageHeight + pageGapPx
        
        val firstVisible = max(0, ((visibleTop - pageGapPx) / pageHeightWithGap).toInt())
        val lastVisible = min(pageCount - 1, (visibleBottom / pageHeightWithGap).toInt() + 1)
        
        // Render visible pages
        for (i in firstVisible..lastVisible) {
            if (!pageCache.containsKey(i) || shouldRerender(i)) {
                renderPage(i)
            }
        }
        
        // Clean up cache - remove pages that are far from visible range
        val pagesToRemove = pageCache.keys.filter { it < firstVisible - 1 || it > lastVisible + 1 }
        pagesToRemove.forEach { pageIndex ->
            pageCache[pageIndex]?.bitmap?.recycle()
            pageCache.remove(pageIndex)
        }
        
        invalidate()
    }

    private fun shouldRerender(pageIndex: Int): Boolean {
        val cached = pageCache[pageIndex] ?: return true
        // Rerender if scale has changed significantly
        return kotlin.math.abs(cached.renderScale - currentScale) > 0.5f
    }

    private fun renderPage(pageIndex: Int) {
        if (pageIndex < 0 || pageIndex >= pageCount) return
        
        try {
            val page = pdfRenderer!!.openPage(pageIndex)
            
            // Render at current scale for quality
            val renderScale = currentScale.coerceIn(1f, 2f)  // Cap render scale for memory
            val bitmapWidth = (pageWidth * renderScale).toInt()
            val bitmapHeight = (pageHeight * renderScale).toInt()
            
            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            
            // Remove old cached bitmap if exists
            pageCache[pageIndex]?.bitmap?.recycle()
            
            pageCache[pageIndex] = CachedPage(pageIndex, bitmap, renderScale)
            
            Log.d(TAG, "Rendered page $pageIndex at scale $renderScale (${bitmapWidth}x${bitmapHeight})")
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering page $pageIndex", e)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        
        if (pdfRenderer == null || pageHeight == 0) return
        
        canvas.save()
        canvas.translate(translateX, translateY)
        canvas.scale(currentScale, currentScale)
        
        val pageHeightWithGap = pageHeight + pageGapPx
        
        // Calculate visible page range
        val scrollY = -translateY / currentScale
        val visibleTop = scrollY
        val visibleBottom = scrollY + height / currentScale
        
        val firstVisible = max(0, ((visibleTop - pageGapPx) / pageHeightWithGap).toInt())
        val lastVisible = min(pageCount - 1, (visibleBottom / pageHeightWithGap).toInt() + 1)
        
        // Draw each visible page
        for (i in firstVisible..lastVisible) {
            val pageTop = pageGapPx + i * pageHeightWithGap
            
            // Draw page shadow
            val shadowRect = RectF(
                pageGapPx.toFloat() - 2,
                pageTop.toFloat() - 2,
                (pageGapPx + pageWidth).toFloat() + 2,
                (pageTop + pageHeight).toFloat() + 4
            )
            canvas.drawRect(shadowRect, shadowPaint)
            
            // Draw page background
            val pageRect = RectF(
                pageGapPx.toFloat(),
                pageTop.toFloat(),
                (pageGapPx + pageWidth).toFloat(),
                (pageTop + pageHeight).toFloat()
            )
            canvas.drawRect(pageRect, pageBgPaint)
            
            // Draw page content
            val cached = pageCache[i]
            if (cached != null) {
                val srcRect = android.graphics.Rect(0, 0, cached.bitmap.width, cached.bitmap.height)
                val dstRect = RectF(
                    pageGapPx.toFloat(),
                    pageTop.toFloat(),
                    (pageGapPx + pageWidth).toFloat(),
                    (pageTop + pageHeight).toFloat()
                )
                canvas.drawBitmap(cached.bitmap, srcRect, dstRect, pagePaint)

                // Draw highlights for this page
                highlights.filter { it.chapterIndex == i }.forEach { highlight ->
                    try {
                        val coords = highlight.rangeData.split(";").flatMap { rectStr ->
                            rectStr.split(",").map { it.toFloat() }
                        }
                        
                        for (j in coords.indices step 4) {
                            if (j + 3 < coords.size) {
                                // Coordinates are relative to page size (0..1)
                                val hLeft = dstRect.left + coords[j] * pageWidth
                                val hTop = dstRect.top + coords[j+1] * pageHeight
                                val hRight = dstRect.left + coords[j+2] * pageWidth
                                val hBottom = dstRect.top + coords[j+3] * pageHeight
                                
                                canvas.drawRect(hLeft, hTop, hRight, hBottom, highlightPaint)
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore invalid highlights
                    }
                }
                
                // Draw current selection
                if (isSelecting && selectionStartPage == i) {
                    selectionRects.forEach { rect ->
                        val sLeft = dstRect.left + rect.left * pageWidth
                        val sTop = dstRect.top + rect.top * pageHeight
                        val sRight = dstRect.left + rect.right * pageWidth
                        val sBottom = dstRect.top + rect.bottom * pageHeight
                        canvas.drawRect(sLeft, sTop, sRight, sBottom, selectionPaint)
                    }
                    
                    // Draw handles
                    if (selectionRects.isNotEmpty()) {
                        val first = selectionRects.first()
                        val last = selectionRects.last()
                        
                        val startX = dstRect.left + first.left * pageWidth
                        val startY = dstRect.top + first.bottom * pageHeight
                        val endX = dstRect.left + last.right * pageWidth
                        val endY = dstRect.top + last.bottom * pageHeight
                        
                        // Start handle (teardrop shape pointing up-right)
                        canvas.drawCircle(startX - handleRadius/2, startY + handleRadius, handleRadius, handlePaint)
                        
                        // End handle (teardrop shape pointing up-left)
                        canvas.drawCircle(endX + handleRadius/2, endY + handleRadius, handleRadius, handlePaint)
                    }
                }
            }
        }
        
        canvas.restore()
    }

    /**
     * Zooms in by a fixed amount
     */
    fun zoomIn() {
        val targetScale = (currentScale * 1.5f).coerceAtMost(MAX_SCALE)
        animateScaleTo(targetScale, width / 2f, height / 2f)
    }

    /**
     * Zooms out by a fixed amount
     */
    fun zoomOut() {
        val targetScale = (currentScale / 1.5f).coerceAtLeast(MIN_SCALE)
        animateScaleTo(targetScale, width / 2f, height / 2f)
    }

    /**
     * Resets zoom to default
     */
    fun resetZoom() {
        animateScaleTo(DEFAULT_SCALE, width / 2f, height / 2f)
    }

    /**
     * Navigate to a specific page
     */
    fun goToPage(pageNumber: Int) {
        val pageIndex = (pageNumber - 1).coerceIn(0, pageCount - 1)
        val targetY = -(pageGapPx + pageIndex * (pageHeight + pageGapPx)) * currentScale
        
        android.animation.ValueAnimator.ofFloat(translateY, targetY).apply {
            duration = 300
            addUpdateListener { animator ->
                translateY = animator.animatedValue as Float
                constrainTranslation()
                updateCurrentPage()
                invalidate()
            }
            start()
        }
    }

    /**
     * Get the current page number (1-indexed)
     */
    fun getCurrentPage(): Int = currentVisiblePage + 1

    /**
     * Get total page count
     */
    fun getPageCount(): Int = pageCount

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        closePdf()
    }
}
