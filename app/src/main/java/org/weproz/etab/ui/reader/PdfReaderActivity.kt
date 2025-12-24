package org.weproz.etab.ui.reader

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.weproz.etab.R
import org.weproz.etab.data.local.database.AppDatabase
import org.weproz.etab.data.local.entity.HighlightEntity
import org.weproz.etab.data.local.database.WordDatabase
import org.weproz.etab.databinding.ActivityPdfReaderBinding
import org.weproz.etab.ui.search.DefinitionDialogFragment
import java.io.File
import java.io.FileInputStream
import java.net.URLEncoder
import android.widget.PopupWindow
import android.view.LayoutInflater
import android.view.ViewGroup
import android.graphics.Color
import org.weproz.etab.ui.custom.CustomDialog
import org.weproz.etab.ui.notes.whiteboard.WhiteboardView
import org.weproz.etab.data.model.whiteboard.DrawAction
import org.weproz.etab.data.serializer.WhiteboardSerializer
import org.weproz.etab.data.model.whiteboard.ParsedPage
import org.weproz.etab.data.model.whiteboard.GridType

class PdfReaderActivity : AppCompatActivity(), PdfReaderBridge {

    private lateinit var binding: ActivityPdfReaderBinding
    private var pdfPath: String? = null
    private var isSplitView = false
    private lateinit var annotationRepository: org.weproz.etab.data.repository.AnnotationRepository
    
    // Annotation Persistence
    private val pageAnnotations = mutableMapOf<Int, List<DrawAction>>()
    private var currentPage = -1
    private var totalPages = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        annotationRepository = org.weproz.etab.data.repository.AnnotationRepository(getExternalFilesDir("annotations")!!)

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        pdfPath = intent.getStringExtra("book_path")

        if (pdfPath != null) {
            setupWebView()
            loadAnnotations()
            loadPdf(pdfPath!!)
            updateLastOpened(pdfPath!!)
        } else {
            Toast.makeText(this, "Error loading PDF", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupControls()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webview.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
        }

        binding.webview.addJavascriptInterface(PdfWebAppInterface(this), "Android")
        binding.webview.webChromeClient = WebChromeClient()
        binding.webview.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                
                // Serve the PDF file
                if (url.startsWith("https://etab.local/book.pdf")) {
                    return try {
                        val file = File(pdfPath!!)
                        WebResourceResponse("application/pdf", "UTF-8", FileInputStream(file))
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                }
                
                // Serve PDF.js assets from "https://etab.local/pdfjs/..."
                if (url.startsWith("https://etab.local/pdfjs/")) {
                    // Remove query params (e.g. ?file=...) to get the actual file path
                    val cleanUrl = url.substringBefore('?')
                    val assetPath = cleanUrl.replace("https://etab.local/", "")
                    
                    return try {
                        val mimeType = when {
                            cleanUrl.endsWith(".html") -> "text/html"
                            cleanUrl.endsWith(".css") -> "text/css"
                            cleanUrl.endsWith(".js") || cleanUrl.endsWith(".mjs") -> "application/javascript"
                            cleanUrl.endsWith(".json") -> "application/json"
                            cleanUrl.endsWith(".svg") -> "image/svg+xml"
                            cleanUrl.endsWith(".gif") -> "image/gif"
                            cleanUrl.endsWith(".png") -> "image/png"
                            cleanUrl.endsWith(".properties") -> "text/plain"
                            else -> "application/octet-stream"
                        }
                        WebResourceResponse(mimeType, "UTF-8", assets.open(assetPath))
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                }
                
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
                injectCustomScripts()
                loadHighlights()
            }
        }
    }

    private fun loadHighlights() {
        lifecycleScope.launch(Dispatchers.IO) {
            val highlights = AppDatabase.getDatabase(this@PdfReaderActivity).highlightDao().getHighlightsForBook(pdfPath!!)
            val jsonArray = org.json.JSONArray()
            highlights.forEach { h ->
                val obj = org.json.JSONObject()
                obj.put("page", h.chapterIndex)
                obj.put("text", h.highlightedText)
                obj.put("rangeData", h.rangeData)
                jsonArray.put(obj)
            }
            
            withContext(Dispatchers.Main) {
                val js = "if(window.restoreHighlights) { window.restoreHighlights($jsonArray); }"
                binding.webview.evaluateJavascript(js, null)
            }
        }
    }

    private fun injectCustomScripts() {
        val js = org.weproz.etab.ui.reader.ReaderScriptUtils.PDF_JS_INJECTION
        binding.webview.evaluateJavascript(js, null)
    }

    private fun loadPdf(path: String) {
        binding.progressBar.visibility = View.VISIBLE
        try {
            // Load the viewer from the same "virtual" domain to avoid CORS issues
            val encodedUrl = URLEncoder.encode("https://etab.local/book.pdf", "UTF-8")
            val viewerUrl = "https://etab.local/pdfjs/web/viewer.html?file=$encodedUrl#pagemode=none"
            binding.webview.loadUrl(viewerUrl)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error initializing PDF viewer", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateLastOpened(path: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(this@PdfReaderActivity).bookDao()
            dao.updateLastOpened(path, System.currentTimeMillis())
        }
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnSplitView.setOnClickListener { toggleSplitView() }

        // Annotation Controls
        binding.annotationView.isTransparentBackground = true
        binding.whiteboardToolbar.attachTo(binding.annotationView)
        
        // Initialize state: Visible but not interactive
        binding.annotationView.visibility = View.VISIBLE
        binding.annotationView.isTouchEnabled = false
        
        binding.btnAnnotateToggle.setOnClickListener {
            val isVisible = binding.whiteboardToolbar.visibility == View.VISIBLE
            if (isVisible) {
                // Hide tools, disable annotation interaction
                binding.whiteboardToolbar.visibility = View.GONE
                binding.annotationView.isTouchEnabled = false
                binding.btnAnnotateToggle.setColorFilter(Color.WHITE)
            } else {
                // Show tools, enable annotation interaction
                binding.whiteboardToolbar.visibility = View.VISIBLE
                binding.annotationView.isTouchEnabled = true
                binding.btnAnnotateToggle.setColorFilter(Color.YELLOW) // Active indicator
            }
        }

        binding.btnOverlayPrev.setOnClickListener {
            binding.webview.evaluateJavascript("window.PDFViewerApplication.page--", null)
            showControlsTemporarily()
        }

        binding.btnOverlayNext.setOnClickListener {
            binding.webview.evaluateJavascript("window.PDFViewerApplication.page++", null)
            showControlsTemporarily()
        }

        binding.btnZoomIn.setOnClickListener {
            binding.webview.evaluateJavascript("window.PDFViewerApplication.zoomIn();", null)
            showControlsTemporarily()
        }
        binding.btnZoomOut.setOnClickListener {
            binding.webview.evaluateJavascript("window.PDFViewerApplication.zoomOut();", null)
            showControlsTemporarily()
        }
        binding.btnZoomReset.setOnClickListener {
            binding.webview.evaluateJavascript("window.PDFViewerApplication.pdfViewer.currentScaleValue = 'page-fit';", null)
            showControlsTemporarily()
        }

        binding.pageSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val page = progress + 1
                    binding.webview.evaluateJavascript("window.PDFViewerApplication.page = $page;", null)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                showControlsTemporarily()
            }
        })

        showControlsTemporarily()
    }

    // Bridge Implementation
    override fun onDefine(word: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            val cleanWord = word.trim().replace(Regex("[^a-zA-Z]"), "")
            if (cleanWord.isEmpty()) return@launch
            
            val dao = WordDatabase.getDatabase(this@PdfReaderActivity).wordDao()
            val definition = dao.getDefinition(cleanWord)

            if (definition != null) {
                DefinitionDialogFragment.newInstance(definition)
                    .show(supportFragmentManager, "definition")
            } else {
                Toast.makeText(this@PdfReaderActivity, "Definition not found for '$cleanWord'", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onHighlight(text: String, page: Int, rangeData: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val highlight = HighlightEntity(
                bookPath = pdfPath!!,
                chapterIndex = page,
                rangeData = rangeData,
                highlightedText = text,
                color = -256
            )
            AppDatabase.getDatabase(this@PdfReaderActivity).highlightDao().insert(highlight)
        }
    }

    override fun onRemoveHighlight(text: String, page: Int, rangeData: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            AppDatabase.getDatabase(this@PdfReaderActivity).highlightDao().deleteHighlight(
                bookPath = pdfPath!!,
                chapterIndex = page,
                rangeData = rangeData
            )
        }
    }

    override fun onCopy(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Copied Text", text)
        clipboard.setPrimaryClip(clip)
        runOnUiThread {
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPageChanged(pageNumber: Int, totalPages: Int) {
        runOnUiThread {
            // Save previous page (or current page if reloading)
            if (currentPage != -1) {
                 pageAnnotations[currentPage] = binding.annotationView.getPaths().toList()
            }
            
            val isSamePage = currentPage == pageNumber
            this.currentPage = pageNumber
            this.totalPages = totalPages
            
            // Update view's current page
            binding.annotationView.currentPage = pageNumber
            
            if (!isSamePage) {
                // Reset clip bounds to avoid stale clipping from previous page
                binding.annotationView.setPdfClipBounds(null)
                
                // Load new page
                val actions = pageAnnotations[pageNumber] ?: emptyList()
                // Ensure actions belong to this page (handling legacy or migration)
                actions.forEach { action ->
                    when(action) {
                        is DrawAction.Stroke -> action.page = pageNumber
                        is DrawAction.Text -> action.page = pageNumber
                    }
                }
                binding.annotationView.loadPaths(actions)
            }

            binding.textPageIndicator.text = "Page $pageNumber of $totalPages"
            if (totalPages > 0) {
                binding.pageSlider.max = totalPages - 1
                binding.pageSlider.progress = pageNumber - 1
            }
        }
    }

    override fun onToggleControls() {
        runOnUiThread {
            if (binding.zoomControls.alpha > 0) {
                hideControlsHandler.post(hideControlsRunnable)
            } else {
                showControlsTemporarily()
            }
        }
    }

    override fun onPrevPage() {
        runOnUiThread {
            binding.webview.evaluateJavascript("window.PDFViewerApplication.page--", null)
            showControlsTemporarily()
        }
    }

    override fun onNextPage() {
        runOnUiThread {
            binding.webview.evaluateJavascript("window.PDFViewerApplication.page++", null)
            showControlsTemporarily()
        }
    }

    private val hideControlsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideControlsRunnable = Runnable {
        binding.zoomControls.animate().alpha(0f).setDuration(300).start()
        binding.bottomControls.animate().alpha(0f).setDuration(300).start()
        binding.btnOverlayPrev.animate().alpha(0f).setDuration(300).start()
        binding.btnOverlayNext.animate().alpha(0f).setDuration(300).start()
    }

    private fun showControlsTemporarily() {
        binding.zoomControls.alpha = 1f
        binding.zoomControls.visibility = View.VISIBLE
        binding.bottomControls.alpha = 1f
        binding.bottomControls.visibility = View.VISIBLE
        binding.btnOverlayPrev.alpha = 1f
        binding.btnOverlayPrev.visibility = View.VISIBLE
        binding.btnOverlayNext.alpha = 1f
        binding.btnOverlayNext.visibility = View.VISIBLE

        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, 4000)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun toggleSplitView() {
        if (isSplitView) {
            val fragment = supportFragmentManager.findFragmentById(R.id.whiteboard_container) as? org.weproz.etab.ui.notes.whiteboard.WhiteboardFragment
            fragment?.saveWhiteboard()

            binding.whiteboardContainer.visibility = View.GONE
            binding.splitHandle.visibility = View.GONE

            val params = binding.webview.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            params.topToBottom = R.id.top_action_bar
            params.height = 0
            binding.webview.layoutParams = params

            isSplitView = false
        } else {
            if (pdfPath != null) {
                val input = android.widget.EditText(this)
                input.hint = "Whiteboard Title"
                
                CustomDialog(this)
                    .setTitle("New Whiteboard")
                    .setView(input)
                    .setPositiveButton("Create") { dialog ->
                        val title = input.text.toString().trim()
                        if (title.isNotEmpty()) {
                            startSplitView(title)
                            dialog.dismiss()
                        } else {
                            Toast.makeText(this, "Title cannot be empty", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel")
                    .show()
            }
        }
    }

    private fun startSplitView(title: String) {
        val timestamp = System.currentTimeMillis()
        val notesPath = File(getExternalFilesDir(null), "wb_pdf_$timestamp.json").absolutePath
        val newFragment = org.weproz.etab.ui.notes.whiteboard.WhiteboardFragment.newInstance(notesPath, title)
        supportFragmentManager.beginTransaction()
            .replace(R.id.whiteboard_container, newFragment)
            .commit()

        binding.whiteboardContainer.visibility = View.VISIBLE
        binding.splitHandle.visibility = View.VISIBLE

        setupSplitDrag()

        val guideParams = binding.splitGuideline.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        guideParams.guidePercent = 0.5f
        binding.splitGuideline.layoutParams = guideParams

        val pdfParams = binding.webview.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        pdfParams.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        pdfParams.bottomToTop = R.id.split_guideline
        pdfParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        pdfParams.topToBottom = R.id.top_action_bar
        pdfParams.height = 0
        binding.webview.layoutParams = pdfParams

        val containerParams = binding.whiteboardContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        containerParams.topToBottom = R.id.split_guideline
        containerParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        containerParams.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        containerParams.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        containerParams.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        containerParams.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        containerParams.height = 0
        containerParams.width = 0
        binding.whiteboardContainer.layoutParams = containerParams

        binding.root.requestLayout()
        isSplitView = true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSplitDrag() {
        binding.splitHandle.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_MOVE -> {
                    val rootHeight = binding.root.height.toFloat()
                    if (rootHeight > 0) {
                        var percent = event.rawY / rootHeight
                        percent = percent.coerceIn(0.2f, 0.8f)
                        
                        val params = binding.splitGuideline.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                        params.guidePercent = percent
                        binding.splitGuideline.layoutParams = params
                    }
                }
            }
            true
        }
    }

    override fun onPageBounds(left: Float, top: Float, right: Float, bottom: Float) {
        runOnUiThread {
            // Convert web coordinates to view coordinates
            val density = resources.displayMetrics.density
            val rect = android.graphics.RectF(
                left * density,
                top * density,
                right * density,
                bottom * density
            )
            // Log for debugging
            // android.util.Log.d("PdfReaderActivity", "onPageBounds: $rect")
            binding.annotationView.setPdfClipBounds(rect)
        }
    }

    override fun onSyncScroll(x: Float, y: Float, scale: Float, page: Int) {
        runOnUiThread {
            binding.annotationView.syncView(x, y, scale, page)
        }
    }

    override fun onPause() {
        super.onPause()
        saveAnnotations()
        if (isSplitView) {
            val fragment = supportFragmentManager.findFragmentById(R.id.whiteboard_container) as? org.weproz.etab.ui.notes.whiteboard.WhiteboardFragment
            fragment?.saveWhiteboard()
        }
    }

    private fun loadAnnotations() {
        if (pdfPath == null) return
        val loaded = annotationRepository.loadAnnotations(pdfPath!!)
        pageAnnotations.clear()
        pageAnnotations.putAll(loaded)
    }

    private fun saveAnnotations() {
        if (pdfPath == null) return
        // Save current page first
        pageAnnotations[currentPage] = binding.annotationView.getPaths().toList()
        annotationRepository.saveAnnotations(pdfPath!!, pageAnnotations)
    }

    override fun onDestroy() {
        super.onDestroy()
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
    }
}
