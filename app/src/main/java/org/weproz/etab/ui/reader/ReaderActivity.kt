package org.weproz.etab.ui.reader

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.siegmann.epublib.epub.EpubReader
import org.weproz.etab.R
import org.weproz.etab.data.local.AppDatabase
import org.weproz.etab.data.local.HighlightEntity
import org.weproz.etab.databinding.ActivityReaderBinding
import org.weproz.etab.ui.search.DefinitionDialogFragment
import java.io.FileInputStream
import android.widget.PopupWindow
import android.view.LayoutInflater
import android.view.ViewGroup
import android.graphics.Color
import android.widget.SeekBar
import org.weproz.etab.ui.custom.CustomDialog
import org.weproz.etab.ui.notes.whiteboard.WhiteboardView
import org.weproz.etab.data.model.whiteboard.DrawAction
import org.weproz.etab.data.serializer.WhiteboardSerializer
import org.weproz.etab.data.model.whiteboard.ParsedPage
import org.weproz.etab.data.model.whiteboard.GridType
import java.io.File

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private var currentBook: nl.siegmann.epublib.domain.Book? = null
    private var currentChapterIndex = 0
    private var bookPath: String? = null
    private var isFirstLoad = true
    private lateinit var annotationRepository: org.weproz.etab.data.repository.AnnotationRepository
    
    // Annotation Persistence
    private val chapterAnnotations = mutableMapOf<Int, List<DrawAction>>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        annotationRepository = org.weproz.etab.data.repository.AnnotationRepository(getExternalFilesDir("annotations")!!)

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars =
                insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        bookPath = intent.getStringExtra("book_path")

        setupWebView()

        if (bookPath != null) {
            loadAnnotations()
            loadBook(bookPath!!)
            // Update last opened time
            lifecycleScope.launch(Dispatchers.IO) {
                val dao = AppDatabase.getDatabase(this@ReaderActivity).bookDao()
                dao.updateLastOpened(bookPath!!, System.currentTimeMillis())
            }
        } else {
            Toast.makeText(this, "Error loading book", Toast.LENGTH_SHORT).show()
            finish()
        }

        setupNavigation()
    }

    override fun onPause() {
        super.onPause()
        saveAnnotations()
        // Save whiteboard when activity is paused
        if (isSplitView) {
            val fragment =
                supportFragmentManager.findFragmentById(R.id.whiteboard_container) as? org.weproz.etab.ui.notes.whiteboard.WhiteboardFragment
            fragment?.saveWhiteboard()
        }
    }

    private val hideNavHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideNavRunnable = Runnable {
        binding.btnOverlayPrev.animate().alpha(0f).setDuration(500).start()
        binding.btnOverlayNext.animate().alpha(0f).setDuration(500).start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupNavigation() {
        // Initial state
        showNavigation()

        // Back button
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Split view button
        binding.btnSplitView.setOnClickListener {
            toggleSplitView()
        }

        // Annotation Controls
        binding.annotationView.isTransparentBackground = true
        binding.whiteboardToolbar.attachTo(binding.annotationView)
        
        binding.btnAnnotateToggle.setOnClickListener {
            val isVisible = binding.whiteboardToolbar.visibility == View.VISIBLE
            if (isVisible) {
                binding.whiteboardToolbar.visibility = View.GONE
                binding.annotationView.visibility = View.GONE
                binding.btnAnnotateToggle.setColorFilter(Color.WHITE)
            } else {
                binding.whiteboardToolbar.visibility = View.VISIBLE
                binding.annotationView.visibility = View.VISIBLE
                binding.btnAnnotateToggle.setColorFilter(Color.YELLOW)
            }
        }

        binding.btnOverlayPrev.setOnClickListener {
            prevChapter()
            showNavigation()
        }

        binding.btnOverlayNext.setOnClickListener {
            nextChapter()
            showNavigation()
        }

        binding.btnNotesToggle.setOnClickListener {
            toggleSplitView()
        }
    }

    private fun showNavigation() {
        binding.btnOverlayPrev.animate().cancel()
        binding.btnOverlayNext.animate().cancel()
        binding.btnOverlayPrev.alpha = 1f
        binding.btnOverlayNext.alpha = 1f
        binding.btnOverlayPrev.visibility = View.VISIBLE
        binding.btnOverlayNext.visibility = View.VISIBLE

        hideNavHandler.removeCallbacks(hideNavRunnable)
        hideNavHandler.postDelayed(hideNavRunnable, 3000) // Hide after 3 seconds
    }

    private fun prevChapter() {
        if (currentChapterIndex > 0) {
            displayChapter(currentChapterIndex - 1)
        }
    }

    private fun nextChapter() {
        currentBook?.let { book ->
            if (currentChapterIndex < book.spine.size() - 1) {
                displayChapter(currentChapterIndex + 1)
            }
        }
    }

    private var isSplitView = false

    private fun toggleSplitView() {
        android.util.Log.d("ReaderActivity", "toggleSplitView: $isSplitView -> ${!isSplitView}")
        if (isSplitView) {
            // Save whiteboard before closing
            val fragment =
                supportFragmentManager.findFragmentById(R.id.whiteboard_container) as? org.weproz.etab.ui.notes.whiteboard.WhiteboardFragment
            fragment?.saveWhiteboard()

            // Restore Full Screen
            binding.whiteboardContainer.visibility = View.GONE
            binding.splitHandle.visibility = View.GONE
            binding.webview.visibility = View.VISIBLE

            val params =
                binding.webview.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.bottomToBottom =
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToTop =
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            params.topToBottom = R.id.top_action_bar
            params.height = 0
            params.matchConstraintPercentHeight = 1.0f // Ensure full height logic works
            binding.webview.layoutParams = params

            isSplitView = false
        } else {
            // Split Screen - create a NEW whiteboard each time
            if (bookPath != null) {
                val input = android.widget.EditText(this)
                input.hint = "Whiteboard Title"

                org.weproz.etab.ui.custom.CustomDialog(this)
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
        // Use timestamp to create unique file path for each session
        val timestamp = System.currentTimeMillis()
        val notesPath =
            java.io.File(getExternalFilesDir(null), "wb_book_$timestamp.json").absolutePath
        val newFragment =
            org.weproz.etab.ui.notes.whiteboard.WhiteboardFragment.newInstance(notesPath, title)
        supportFragmentManager.beginTransaction().replace(R.id.whiteboard_container, newFragment)
            .commit()

        binding.whiteboardContainer.visibility = View.VISIBLE
        binding.splitHandle.visibility = View.VISIBLE

        setupSplitDrag()

        // Force Guideline to 50%
        val guideParams =
            binding.splitGuideline.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        guideParams.guidePercent = 0.5f
        binding.splitGuideline.layoutParams = guideParams

        // WebView Constraints
        val webParams =
            binding.webview.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        webParams.bottomToBottom =
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        webParams.bottomToTop = R.id.split_guideline
        webParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        webParams.topToBottom = R.id.top_action_bar
        webParams.height = 0
        // webParams.matchConstraintPercentHeight = 1.0f // REMOVED to allow anchors to determine height
        binding.webview.layoutParams = webParams

        // Container Constraints
        val containerParams =
            binding.whiteboardContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        containerParams.topToBottom = R.id.split_guideline
        containerParams.topToTop =
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        containerParams.bottomToBottom =
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        containerParams.bottomToTop =
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        containerParams.startToStart =
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        containerParams.endToEnd =
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        containerParams.height = 0
        containerParams.width = 0 // Match Constraint
        binding.whiteboardContainer.layoutParams = containerParams

        binding.root.requestLayout()
        isSplitView = true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSplitDrag() {
        binding.splitHandle.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_MOVE -> {
                    // Calculate percent based on touch Y relative to root height
                    val rootHeight = binding.root.height.toFloat()
                    if (rootHeight > 0) {
                        // We use the rawY to get global position, but we need relative to root if root is offset?
                        // Assuming ReaderActivity is full screen essentially.
                        var percent = event.rawY / rootHeight

                        // Clamp
                        if (percent < 0.2f) percent = 0.2f
                        if (percent > 0.8f) percent = 0.8f

                        val params =
                            binding.splitGuideline.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                        params.guidePercent = percent
                        binding.splitGuideline.layoutParams = params
                    }
                }
            }
            true
        }
    }

    private fun setupWebView() {
        binding.webview.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            loadsImagesAutomatically = true
        }

        binding.webview.addJavascriptInterface(WebAppInterface(), "Android")
        binding.webview.webChromeClient = WebChromeClient()
        binding.webview.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progressBar.visibility = View.GONE
                restoreHighlights()
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                // We use a custom local domain to avoid file:// security restrictions
                val localDomain = "https://etab.local/"

                if (url.startsWith(localDomain)) {
                    val relativePath = url.removePrefix(localDomain)
                    val decodedPath = java.net.URLDecoder.decode(relativePath, "UTF-8")

                    android.util.Log.d(
                        "ReaderActivity",
                        "Requesting: $url -> Decoded: $decodedPath"
                    )

                    // Logic to find resource:
                    // Requests might be "OEBPS/images/img.jpg" or just "images/img.jpg" depending on resolution.
                    // We try to find a resource whose href ends with the requested path.

                    var resource = currentBook?.resources?.getByHref(decodedPath)

                    if (resource == null) {
                        currentBook?.resources?.all?.forEach { res ->
                            // More robust fuzzy match:
                            // If res.href is "OEBPS/images/cover.jpg" and request is "images/cover.jpg", it matches.
                            if (res.href == decodedPath || res.href.endsWith(decodedPath) || decodedPath.endsWith(
                                    res.href
                                )
                            ) {
                                resource = res
                                return@forEach
                            }
                        }
                    }

                    if (resource != null) {
                        android.util.Log.d("ReaderActivity", "Found resource: ${resource.href}")
                        return android.webkit.WebResourceResponse(
                            resource.mediaType.name,
                            "UTF-8",
                            resource.inputStream
                        )
                    } else {
                        android.util.Log.w("ReaderActivity", "Resource NOT found for: $decodedPath")
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun loadBook(path: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                android.util.Log.d("ReaderActivity", "Loading book from: $path")
                val inputStream = FileInputStream(path)
                currentBook = EpubReader().readEpub(inputStream)
                android.util.Log.d(
                    "ReaderActivity",
                    "Book loaded. Title: ${currentBook?.title}, Spine size: ${currentBook?.spine?.size()}"
                )

                // Get last read chapter
                val dao = AppDatabase.getDatabase(this@ReaderActivity).bookDao()
                val lastReadChapter = dao.getLastReadPage(path) ?: 0

                withContext(Dispatchers.Main) {
                    val spineSize = currentBook?.spine?.size() ?: 0
                    val chapterToOpen = if (lastReadChapter > 0 && lastReadChapter < spineSize) {
                        lastReadChapter
                    } else {
                        0
                    }
                    displayChapter(chapterToOpen)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                android.util.Log.e("ReaderActivity", "Error loading book", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ReaderActivity, "Failed to parse epub", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    private fun displayChapter(index: Int) {
        if (!isFirstLoad) {
             chapterAnnotations[currentChapterIndex] = binding.annotationView.getPaths().toList()
        }
        isFirstLoad = false
        
        currentChapterIndex = index
        
        // Load annotations for new chapter
        val actions = chapterAnnotations[index] ?: emptyList()
        binding.annotationView.loadPaths(actions)

        currentBook?.let { book ->
            if (index < book.spine.size()) {
                val resource = book.spine.getResource(index)
                android.util.Log.d(
                    "ReaderActivity",
                    "Displaying chapter $index. Resource href: ${resource.href}, Size: ${resource.size}"
                )
                val rawContent = String(resource.data)

                // Save current chapter to database
                saveLastReadChapter(index)

                // Inject JS and CSS
                val content = injectCustomContent(rawContent)

                // Use HTTPS base URL to allow confident interception and avoid file:// restrictions
                // We append the directory of the current chapter to the base so relative links work.
                // e.g. if chapter is "OEBPS/content.html", base is "https://etab.local/OEBPS/"
                // So <img src="../img.jpg"> becomes "https://etab.local/img.jpg"

                // Simple approach: Just use the href as the "path" part of the URL
                val baseUrl = "https://etab.local/" + resource.href

                android.util.Log.d("ReaderActivity", "Loading into WebView with BaseURL: $baseUrl")
                binding.webview.loadDataWithBaseURL(baseUrl, content, "text/html", "UTF-8", null)
            } else {
                android.util.Log.e("ReaderActivity", "Invalid chapter index: $index")
            }
        } ?: run {
            android.util.Log.e("ReaderActivity", "Current book is null")
        }
    }

    private fun saveLastReadChapter(chapter: Int) {
        bookPath?.let { path ->
            lifecycleScope.launch(Dispatchers.IO) {
                val dao = AppDatabase.getDatabase(this@ReaderActivity).bookDao()
                dao.updateLastReadPage(path, chapter)
            }
        }
    }

    private fun injectCustomContent(html: String): String {
        val js = org.weproz.etab.ui.reader.ReaderScriptUtils.EPUB_JS_INJECTION
        // Insert before </body>
        return html.replace("</body>", "$js</body>")
    }

    private fun restoreHighlights() {
        bookPath?.let { path ->
            lifecycleScope.launch {
                val dao = AppDatabase.getDatabase(this@ReaderActivity).highlightDao()
                val highlights = dao.getHighlightsForChapter(path, currentChapterIndex)
                highlights.forEach { highlight ->
                    // Escape single quotes for JS string
                    val safeText = highlight.highlightedText.replace("'", "\\'")
                    val rangeData = highlight.rangeData ?: "0"
                    binding.webview.evaluateJavascript(
                        "restoreHighlight('$safeText', '$rangeData')",
                        null
                    )
                }
            }
        }
    }

    private fun loadAnnotations() {
        if (bookPath == null) return
        val loaded = annotationRepository.loadAnnotations(bookPath!!)
        chapterAnnotations.clear()
        // Convert 1-based (repository) to 0-based (chapter index)
        loaded.forEach { (page, actions) ->
            chapterAnnotations[page - 1] = actions
        }
    }

    private fun saveAnnotations() {
        if (bookPath == null) return
        // Save current chapter first
        chapterAnnotations[currentChapterIndex] = binding.annotationView.getPaths().toList()
        
        // Convert 0-based (chapter index) to 1-based (repository)
        val toSave = mutableMapOf<Int, List<DrawAction>>()
        chapterAnnotations.forEach { (chapter, actions) ->
            toSave[chapter + 1] = actions
        }
        
        annotationRepository.saveAnnotations(bookPath!!, toSave)
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun onDefine(word: String) {
            lifecycleScope.launch(Dispatchers.Main) {
                val dao = org.weproz.etab.data.local.WordDatabase.getDatabase(this@ReaderActivity)
                    .wordDao()
                // Try exact match first, then clean up
                val cleanWord = word.replace(Regex("[^a-zA-Z]"), "")
                val definition = dao.getDefinition(cleanWord)

                if (definition != null) {
                    DefinitionDialogFragment.newInstance(definition)
                        .show(supportFragmentManager, "definition")
                } else {
                    Toast.makeText(
                        this@ReaderActivity,
                        "Definition not found for '$cleanWord'",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        @JavascriptInterface
        fun onHighlight(text: String, rangeData: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                val highlight = HighlightEntity(
                    bookPath = bookPath!!,
                    chapterIndex = currentChapterIndex,
                    rangeData = rangeData,
                    highlightedText = text,
                    color = -256 // Yellow
                )
                AppDatabase.getDatabase(this@ReaderActivity).highlightDao().insert(highlight)
            }
        }

        @JavascriptInterface
        fun onRemoveHighlight(text: String, rangeData: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                AppDatabase.getDatabase(this@ReaderActivity).highlightDao().deleteHighlight(
                    bookPath = bookPath!!,
                    chapterIndex = currentChapterIndex,
                    rangeData = rangeData
                )
            }
        }

        @JavascriptInterface
        fun onCopy(text: String) {
            val clipboard =
                getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Copied Text", text)
            clipboard.setPrimaryClip(clip)
            runOnUiThread {
                Toast.makeText(this@ReaderActivity, "Copied to clipboard", Toast.LENGTH_SHORT)
                    .show()
            }
        }

        @JavascriptInterface
        fun onPrevPage() {
            runOnUiThread {
                prevChapter()
                showNavigation()
            }
        }

        @JavascriptInterface
        fun onNextPage() {
            runOnUiThread {
                nextChapter()
                showNavigation()
            }
        }

        @JavascriptInterface
        fun onToggleControls() {
            runOnUiThread {
                showNavigation()
            }
        }
    }
}
