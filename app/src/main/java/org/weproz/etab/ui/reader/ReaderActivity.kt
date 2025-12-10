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

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private var currentBook: nl.siegmann.epublib.domain.Book? = null
    private var currentChapterIndex = 0
    private var bookPath: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        bookPath = intent.getStringExtra("book_path")

        setupWebView()

        if (bookPath != null) {
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
        // Save whiteboard when activity is paused
        if (isSplitView) {
            val fragment = supportFragmentManager.findFragmentById(R.id.whiteboard_container) as? org.weproz.etab.ui.notes.whiteboard.WhiteboardFragment
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
        
        val gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                val width = binding.webview.width
                val x = e.x
                
                // Check if tapping a link?
                // For now, strict zones.
                if (x < width * 0.25) { // Left 25%
                    prevChapter()
                    showNavigation()
                    return true
                } else if (x > width * 0.75) { // Right 25%
                    nextChapter()
                    showNavigation()
                    return true
                } else {
                    // Center tap - just toggle/show controls
                    showNavigation()
                }
                return false
            }
            
            override fun onDown(e: android.view.MotionEvent): Boolean = false
        })
        
        binding.webview.setOnTouchListener { v, event ->
             gestureDetector.onTouchEvent(event)
             // Return false to allow WebView to handle other touch events (scrolling, links)
             false 
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
            val fragment = supportFragmentManager.findFragmentById(R.id.whiteboard_container) as? org.weproz.etab.ui.notes.whiteboard.WhiteboardFragment
            fragment?.saveWhiteboard()

            // Restore Full Screen
            binding.whiteboardContainer.visibility = View.GONE
            binding.splitHandle.visibility = View.GONE
            binding.webview.visibility = View.VISIBLE
            
            val params = binding.webview.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            params.height = 0
            params.matchConstraintPercentHeight = 1.0f // Ensure full height logic works
            binding.webview.layoutParams = params
            
            isSplitView = false
        } else {
            // Split Screen - create a NEW whiteboard each time
            if (bookPath != null) {
                // Use timestamp to create unique file path for each session
                val timestamp = System.currentTimeMillis()
                val notesPath = java.io.File(getExternalFilesDir(null), "wb_book_$timestamp.json").absolutePath
                val newFragment = org.weproz.etab.ui.notes.whiteboard.WhiteboardFragment.newInstance(notesPath)
                supportFragmentManager.beginTransaction().replace(R.id.whiteboard_container, newFragment).commit()
            }
            
            binding.whiteboardContainer.visibility = View.VISIBLE
            binding.splitHandle.visibility = View.VISIBLE
            
            setupSplitDrag()
            
            // Force Guideline to 50%
            val guideParams = binding.splitGuideline.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            guideParams.guidePercent = 0.5f
            binding.splitGuideline.layoutParams = guideParams
            
            // WebView Constraints
            val webParams = binding.webview.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            webParams.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            webParams.bottomToTop = R.id.split_guideline
            webParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            webParams.height = 0
            // webParams.matchConstraintPercentHeight = 1.0f // REMOVED to allow anchors to determine height
            binding.webview.layoutParams = webParams

            // Container Constraints
            val containerParams = binding.whiteboardContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            containerParams.topToBottom = R.id.split_guideline
            containerParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            containerParams.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            containerParams.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            containerParams.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            containerParams.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            containerParams.height = 0
            containerParams.width = 0 // Match Constraint
            binding.whiteboardContainer.layoutParams = containerParams
            
            binding.root.requestLayout()
            isSplitView = true
        }
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
                        
                        val params = binding.splitGuideline.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
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

            override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                
                // We use a custom local domain to avoid file:// security restrictions
                val localDomain = "https://etab.local/"
                
                if (url.startsWith(localDomain)) {
                    val relativePath = url.removePrefix(localDomain)
                    val decodedPath = java.net.URLDecoder.decode(relativePath, "UTF-8")
                    
                    android.util.Log.d("ReaderActivity", "Requesting: $url -> Decoded: $decodedPath")
                    
                    // Logic to find resource:
                    // Requests might be "OEBPS/images/img.jpg" or just "images/img.jpg" depending on resolution.
                    // We try to find a resource whose href ends with the requested path.
                    
                    var resource = currentBook?.resources?.getByHref(decodedPath)
                    
                    if (resource == null) {
                         currentBook?.resources?.all?.forEach { res ->
                             // More robust fuzzy match:
                             // If res.href is "OEBPS/images/cover.jpg" and request is "images/cover.jpg", it matches.
                             if (res.href == decodedPath || res.href.endsWith(decodedPath) || decodedPath.endsWith(res.href)) {
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
        // ... (No change needed here)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                android.util.Log.d("ReaderActivity", "Loading book from: $path")
                val inputStream = FileInputStream(path)
                currentBook = EpubReader().readEpub(inputStream)
                android.util.Log.d("ReaderActivity", "Book loaded. Title: ${currentBook?.title}, Spine size: ${currentBook?.spine?.size()}")
                withContext(Dispatchers.Main) {
                    displayChapter(0)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                android.util.Log.e("ReaderActivity", "Error loading book", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ReaderActivity, "Failed to parse epub", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun displayChapter(index: Int) {
        currentChapterIndex = index
        currentBook?.let { book ->
            if (index < book.spine.size()) {
                val resource = book.spine.getResource(index)
                android.util.Log.d("ReaderActivity", "Displaying chapter $index. Resource href: ${resource.href}, Size: ${resource.size}")
                val rawContent = String(resource.data)
                
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

    private fun injectCustomContent(html: String): String {
        val js = """
            <style>
                ::selection { background: transparent; }
                #custom-menu {
                    position: absolute;
                    background: #333;
                    color: white;
                    padding: 8px;
                    border-radius: 4px;
                    display: none;
                    z-index: 1000;
                    box-shadow: 0 2px 5px rgba(0,0,0,0.2);
                }
                #custom-menu button {
                    background: transparent;
                    border: none;
                    color: white;
                    padding: 4px 8px;
                    font-size: 14px;
                }
                .highlighted {
                    background-color: yellow;
                }
            </style>
            <div id="custom-menu">
                <button onclick="defineWord()">Define</button>
                <div style="width: 1px; height: 16px; background: #555; display: inline-block; vertical-align: middle; margin: 0 4px;"></div>
                <button onclick="highlightText()">Highlight</button>
            </div>
            <script>
                var selectedText = "";
                var selectionRange = null;

                document.addEventListener('selectionchange', function() {
                    var selection = window.getSelection();
                    var menu = document.getElementById('custom-menu');
                    
                    if (selection.toString().length > 0) {
                        selectedText = selection.toString();
                        selectionRange = selection.getRangeAt(0);
                        
                        var rect = selectionRange.getBoundingClientRect();
                        menu.style.top = (window.scrollY + rect.top - 40) + 'px';
                        menu.style.left = (window.scrollX + rect.left) + 'px';
                        menu.style.display = 'block';
                    } else {
                        // Don't hide immediately to allow clicking buttons, 
                        // but actually selection clears when clicking button? 
                        // We need to handle mousedown on menu to prevent clearing selection?
                        // Or just use touch events.
                        // For simplicity, we hide if selection is empty.
                         menu.style.display = 'none';
                    }
                });

                function defineWord() {
                    Android.onDefine(selectedText.trim());
                    document.getElementById('custom-menu').style.display = 'none';
                }

                function highlightText() {
                    if (selectionRange) {
                        var span = document.createElement('span');
                        span.className = 'highlighted';
                        span.textContent = selectedText;
                        selectionRange.deleteContents();
                        selectionRange.insertNode(span);
                        
                        // Serialize range (simplified)
                        // In real app, use a robust CFI or XPath. 
                        // Here we just send the text and index for demo.
                        Android.onHighlight(selectedText, "dummy_range_data");
                        
                        window.getSelection().removeAllRanges();
                        document.getElementById('custom-menu').style.display = 'none';
                    }
                }
                
                function restoreHighlight(text) {
                    // Escape regex special characters
                    var escapedText = text.replace(/[.*+?^${'$'}{}()[\]\\]/g, '\\${'$'}&');
                    
                    var body = document.body.innerHTML;
                    // Use a more robust replacement that doesn't replace inside existing tags if possible,
                    // but for MVP replacing text content is tricky with simple regex on innerHTML.
                    // Risk: replacing attributes. e.g. highlighting "class".
                    // Improved regex: Match text not inside tags. (Very complex, staying simple for now but safer regex)
                    
                    var newBody = body.replace(new RegExp(escapedText, 'g'), '<span class="highlighted">' + text + '</span>');
                    document.body.innerHTML = newBody;
                }
            </script>
        """
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
                    binding.webview.evaluateJavascript("restoreHighlight('$safeText')", null)
                }
            }
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun onDefine(word: String) {
            lifecycleScope.launch(Dispatchers.Main) {
                val dao = org.weproz.etab.data.local.WordDatabase.getDatabase(this@ReaderActivity).wordDao()
                // Try exact match first, then clean up
                val cleanWord = word.replace(Regex("[^a-zA-Z]"), "")
                val definition = dao.getDefinition(cleanWord)
                
                if (definition != null) {
                    DefinitionDialogFragment.newInstance(definition).show(supportFragmentManager, "definition")
                } else {
                    Toast.makeText(this@ReaderActivity, "Definition not found for '$cleanWord'", Toast.LENGTH_SHORT).show()
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
    }
}
