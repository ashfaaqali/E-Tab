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
            val systemBars =
                insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
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
        currentChapterIndex = index
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
        val js = """
            <style>
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
                .separator {
                    width: 1px; 
                    height: 16px; 
                    background: #555; 
                    display: inline-block; 
                    vertical-align: middle; 
                    margin: 0 4px;
                }
                .highlighted {
                    background-color: yellow;
                }
            </style>
            <div id="custom-menu">
                <button id="btn-define" onclick="defineWord()">Define</button>
                <div class="separator"></div>
                <button id="btn-highlight" onclick="highlightText()">Highlight</button>
                <div class="separator"></div>
                <button id="btn-copy" onclick="copyText()">Copy</button>
                <button id="btn-remove" onclick="removeHighlight()" style="display:none">Remove Highlight</button>
            </div>
            <script>
                var selectedText = "";
                var selectionRange = null;
                var clickedHighlight = null;

                function resetMenuButtons() {
                    document.getElementById('btn-define').style.display = 'inline-block';
                    document.getElementById('btn-highlight').style.display = 'inline-block';
                    document.getElementById('btn-copy').style.display = 'inline-block';
                    var separators = document.getElementsByClassName('separator');
                    for(var i=0; i<separators.length; i++) separators[i].style.display = 'inline-block';
                    document.getElementById('btn-remove').style.display = 'none';
                }

                document.addEventListener('selectionchange', function() {
                    var selection = window.getSelection();
                    var menu = document.getElementById('custom-menu');
                    
                    if (selection.toString().length > 0) {
                        selectedText = selection.toString();
                        selectionRange = selection.getRangeAt(0);
                        
                        // Check if selection is inside a highlight OR contains a highlight
                        var isHighlighted = false;
                        
                        // 1. Check if selection is inside a highlight (ancestor check)
                        var parent = selectionRange.commonAncestorContainer;
                        if (parent.nodeType === 3) { // Text node
                            parent = parent.parentNode;
                        }
                        if (parent.classList.contains('highlighted')) {
                            isHighlighted = true;
                            clickedHighlight = parent;
                        }
                        
                        // 2. Check if selection contains any highlighted elements (descendant check)
                        if (!isHighlighted) {
                            var div = document.createElement('div');
                            div.appendChild(selectionRange.cloneContents());
                            if (div.querySelector('.highlighted')) {
                                isHighlighted = true;
                                clickedHighlight = null; // Will need to find it during removal
                            }
                        }
                        
                        if (isHighlighted) {
                            // Show Define, Remove Highlight, Copy
                            document.getElementById('btn-define').style.display = 'inline-block';
                            document.getElementById('btn-highlight').style.display = 'none';
                            document.getElementById('btn-copy').style.display = 'inline-block';
                            document.getElementById('btn-remove').style.display = 'inline-block';
                            
                            // Ensure separators are visible
                            var separators = document.getElementsByClassName('separator');
                            for(var i=0; i<separators.length; i++) separators[i].style.display = 'inline-block';
                        } else {
                            // Show Define, Highlight, Copy
                            resetMenuButtons();
                        }
                        
                        var rect = selectionRange.getBoundingClientRect();
                        var scrollTop = window.scrollY || document.documentElement.scrollTop;
                        var scrollLeft = window.scrollX || document.documentElement.scrollLeft;
                        
                        menu.style.display = 'block';
                        var menuWidth = menu.offsetWidth;
                        var menuHeight = menu.offsetHeight;
                        
                        var top = scrollTop + rect.top - menuHeight - 10;
                        var left = scrollLeft + rect.left + (rect.width / 2) - (menuWidth / 2);
                        
                        if (top < scrollTop) top = scrollTop + rect.bottom + 10;
                        if (left < 0) left = 10;
                        if (left + menuWidth > window.innerWidth) left = window.innerWidth - menuWidth - 10;
                        
                        menu.style.top = top + 'px';
                        menu.style.left = left + 'px';
                    } else {
                        menu.style.display = 'none';
                    }
                });

                document.addEventListener('click', function(e) {
                    var menu = document.getElementById('custom-menu');
                    
                    // 1. Check if clicked on menu or inside menu
                    if (menu.contains(e.target)) {
                        return; // Let button handlers work
                    }

                    // 2. Check if clicked on a highlight
                    if (e.target.classList.contains('highlighted')) {
                        clickedHighlight = e.target;
                        selectedText = e.target.textContent;
                        
                        // Show Define, Remove Highlight, Copy
                        document.getElementById('btn-define').style.display = 'inline-block';
                        document.getElementById('btn-highlight').style.display = 'none';
                        document.getElementById('btn-copy').style.display = 'inline-block';
                        document.getElementById('btn-remove').style.display = 'inline-block';
                        
                        var separators = document.getElementsByClassName('separator');
                        for(var i=0; i<separators.length; i++) separators[i].style.display = 'inline-block';
                        
                        menu.style.display = 'block';
                        
                        var rect = e.target.getBoundingClientRect();
                        var scrollTop = window.scrollY || document.documentElement.scrollTop;
                        var scrollLeft = window.scrollX || document.documentElement.scrollLeft;
                        
                        // Force layout to get correct dimensions
                        var menuWidth = menu.offsetWidth; 
                        if (menuWidth === 0) menuWidth = 150; // Fallback
                        
                        var top = scrollTop + rect.top - menu.offsetHeight - 10;
                        var left = scrollLeft + rect.left + (rect.width / 2) - (menuWidth / 2);
                        
                        if (top < scrollTop) top = scrollTop + rect.bottom + 10;
                        if (left < 0) left = 10;
                        if (left + menuWidth > window.innerWidth) left = window.innerWidth - menuWidth - 10;
                        
                        menu.style.top = top + 'px';
                        menu.style.left = left + 'px';
                        
                        e.stopPropagation();
                        return;
                    } 
                    
                    // 3. Clicked elsewhere
                    
                    // If menu was visible, hide it and don't navigate
                    if (menu.style.display === 'block') {
                         menu.style.display = 'none';
                         resetMenuButtons();
                         return;
                    }
                    
                    // 4. Navigation Logic
                    var width = window.innerWidth;
                    var x = e.clientX;
                    
                    // Only navigate if not selecting text (selection usually implies drag, but click is click)
                    // If selection is non-empty, we probably shouldn't navigate?
                    // But selectionchange handles showing the menu.
                    // If I click to clear selection, selection becomes empty.
                    
                    if (window.getSelection().toString().length > 0) {
                        return;
                    }
                    
                    if (x < width * 0.25) {
                        Android.onPrevPage();
                    } else if (x > width * 0.75) {
                        Android.onNextPage();
                    } else {
                        Android.onToggleControls();
                    }
                });

                function defineWord() {
                    Android.onDefine(selectedText.trim());
                    document.getElementById('custom-menu').style.display = 'none';
                }

                function copyText() {
                    Android.onCopy(selectedText);
                    document.getElementById('custom-menu').style.display = 'none';
                }

                function removeHighlight() {
                    var selection = window.getSelection();
                    
                    // Case 1: Selection-based removal (Partial or Full overlap)
                    if (selection.rangeCount > 0 && !selection.getRangeAt(0).collapsed) {
                        var range = selection.getRangeAt(0);
                        
                        // Find all highlights intersecting the range
                        var highlights = document.querySelectorAll('.highlighted');
                        var highlightsToProcess = [];
                        
                        for (var i = 0; i < highlights.length; i++) {
                            if (range.intersectsNode(highlights[i])) {
                                highlightsToProcess.push(highlights[i]);
                            }
                        }
                        
                        highlightsToProcess.forEach(function(el) {
                            // 1. Remove old from DB
                            var oldText = el.textContent;
                            var oldRangeData = el.getAttribute('data-range');
                            Android.onRemoveHighlight(oldText, oldRangeData);
                            
                            // 2. Calculate split points
                            var fullText = el.textContent;
                            var elRange = document.createRange();
                            elRange.selectNodeContents(el);
                            
                            var startOffset = 0;
                            var endOffset = fullText.length;
                            
                            // Check start
                            if (range.compareBoundaryPoints(Range.START_TO_START, elRange) > 0) {
                                // Selection starts inside this element
                                // We assume the highlight span contains a single text node
                                if (el.firstChild && el.firstChild.nodeType === 3) {
                                     // If selection starts in this text node
                                     if (range.startContainer === el.firstChild) {
                                         startOffset = range.startOffset;
                                     }
                                }
                            }
                            
                            // Check end
                            if (range.compareBoundaryPoints(Range.END_TO_END, elRange) < 0) {
                                // Selection ends inside this element
                                if (el.firstChild && el.firstChild.nodeType === 3) {
                                     if (range.endContainer === el.firstChild) {
                                         endOffset = range.endOffset;
                                     }
                                }
                            }
                            
                            // 3. Create new nodes
                            var fragment = document.createDocumentFragment();
                            
                            // Pre-fragment (Keep highlighted)
                            if (startOffset > 0) {
                                var span1 = document.createElement('span');
                                span1.className = 'highlighted';
                                span1.textContent = fullText.substring(0, startOffset);
                                span1.setAttribute('data-needs-index', 'true');
                                fragment.appendChild(span1);
                            }
                            
                            // Middle-fragment (Remove highlight)
                            var textNode = document.createTextNode(fullText.substring(startOffset, endOffset));
                            fragment.appendChild(textNode);
                            
                            // Post-fragment (Keep highlighted)
                            if (endOffset < fullText.length) {
                                var span2 = document.createElement('span');
                                span2.className = 'highlighted';
                                span2.textContent = fullText.substring(endOffset);
                                span2.setAttribute('data-needs-index', 'true');
                                fragment.appendChild(span2);
                            }
                            
                            el.parentNode.replaceChild(fragment, el);
                        });
                        
                        // 4. Update DB for new fragments
                        // We need to process them in document order to get correct indices
                        var newHighlights = document.querySelectorAll('span[data-needs-index="true"]');
                        newHighlights.forEach(function(span) {
                            span.removeAttribute('data-needs-index');
                            var text = span.textContent;
                            
                            // Calculate index
                            var index = 0;
                            var tempRange = document.createRange();
                            tempRange.selectNodeContents(document.body);
                            tempRange.setEndBefore(span);
                            var precedingText = tempRange.toString();
                            
                            var count = 0;
                            var pos = precedingText.indexOf(text);
                            while (pos !== -1) {
                                count++;
                                pos = precedingText.indexOf(text, pos + 1);
                            }
                            index = count;
                            
                            span.setAttribute('data-range', index.toString());
                            Android.onHighlight(text, index.toString());
                        });
                        
                        selection.removeAllRanges();
                    } 
                    // Case 2: Click-based removal (No selection range, just a click on highlight)
                    else if (clickedHighlight) {
                        var text = clickedHighlight.textContent;
                        var rangeData = clickedHighlight.getAttribute('data-range');
                        
                        Android.onRemoveHighlight(text, rangeData);
                        
                        var parent = clickedHighlight.parentNode;
                        parent.replaceChild(document.createTextNode(text), clickedHighlight);
                        parent.normalize();
                        
                        clickedHighlight = null;
                    }
                    
                    document.getElementById('custom-menu').style.display = 'none';
                    resetMenuButtons();
                }

                function highlightText() {
                    if (selectionRange) {
                        var index = 0;
                        var tempRange = document.createRange();
                        tempRange.selectNodeContents(document.body);
                        tempRange.setEnd(selectionRange.startContainer, selectionRange.startOffset);
                        var precedingText = tempRange.toString();
                        
                        var count = 0;
                        var pos = precedingText.indexOf(selectedText);
                        while (pos !== -1) {
                            count++;
                            pos = precedingText.indexOf(selectedText, pos + 1);
                        }
                        index = count;

                        var span = document.createElement('span');
                        span.className = 'highlighted';
                        span.textContent = selectedText;
                        span.setAttribute('data-range', index.toString());
                        selectionRange.deleteContents();
                        selectionRange.insertNode(span);
                        
                        Android.onHighlight(selectedText, index.toString());
                        
                        window.getSelection().removeAllRanges();
                        document.getElementById('custom-menu').style.display = 'none';
                    }
                }
                
                function restoreHighlight(text, indexStr) {
                    var targetIndex = parseInt(indexStr);
                    if (isNaN(targetIndex)) targetIndex = 0;
                    
                    var walker = document.createTreeWalker(
                        document.body,
                        NodeFilter.SHOW_TEXT,
                        null,
                        false
                    );

                    var currentNode;
                    var matchCount = 0;
                    
                    while (currentNode = walker.nextNode()) {
                        var nodeValue = currentNode.nodeValue;
                        var pos = nodeValue.indexOf(text);
                        
                        while (pos !== -1) {
                            if (matchCount === targetIndex) {
                                var range = document.createRange();
                                range.setStart(currentNode, pos);
                                range.setEnd(currentNode, pos + text.length);
                                
                                var span = document.createElement('span');
                                span.className = 'highlighted';
                                span.textContent = text;
                                span.setAttribute('data-range', indexStr);
                                range.deleteContents();
                                range.insertNode(span);
                                return;
                            }
                            matchCount++;
                            pos = nodeValue.indexOf(text, pos + 1);
                        }
                    }
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
                    val rangeData = highlight.rangeData ?: "0"
                    binding.webview.evaluateJavascript(
                        "restoreHighlight('$safeText', '$rangeData')",
                        null
                    )
                }
            }
        }
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
