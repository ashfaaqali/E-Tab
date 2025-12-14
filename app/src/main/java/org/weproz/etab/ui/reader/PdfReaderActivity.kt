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
import org.weproz.etab.data.local.AppDatabase
import org.weproz.etab.data.local.HighlightEntity
import org.weproz.etab.data.local.WordDatabase
import org.weproz.etab.databinding.ActivityPdfReaderBinding
import org.weproz.etab.ui.search.DefinitionDialogFragment
import java.io.File
import java.io.FileInputStream
import java.net.URLEncoder

class PdfReaderActivity : AppCompatActivity(), PdfReaderBridge {

    private lateinit var binding: ActivityPdfReaderBinding
    private var pdfPath: String? = null
    private var isSplitView = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        pdfPath = intent.getStringExtra("book_path")

        if (pdfPath != null) {
            setupWebView()
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
        val js = """
            // 1. Hide Default Toolbar
            var style = document.createElement('style');
            style.innerHTML = '.toolbar { display: none !important; } #viewerContainer { top: 0 !important; } .highlight-span { background-color: rgba(255, 235, 59, 0.5); }';
            document.head.appendChild(style);

            // 2. Global Highlights Store
            window.savedHighlights = [];
            window.restoreHighlights = function(data) {
                window.savedHighlights = data;
            };

            // 3. Robust Highlight Function (Handles multi-node/multi-line)
            function highlightRange(range, color, dataStr) {
                var nodeIterator = document.createNodeIterator(
                    range.commonAncestorContainer,
                    NodeFilter.SHOW_TEXT,
                    {
                        acceptNode: function(node) {
                            if (!range.intersectsNode(node)) return NodeFilter.FILTER_REJECT;
                            return NodeFilter.FILTER_ACCEPT;
                        }
                    }
                );

                var nodes = [];
                var node;
                while ((node = nodeIterator.nextNode())) {
                    nodes.push(node);
                }

                nodes.forEach(function(node) {
                    var start = (node === range.startContainer) ? range.startOffset : 0;
                    var end = (node === range.endContainer) ? range.endOffset : node.textContent.length;

                    if (start === 0 && end === node.textContent.length) {
                        var span = document.createElement('span');
                        span.className = 'highlight-span';
                        if (dataStr) span.dataset.range = dataStr;
                        node.parentNode.replaceChild(span, node);
                        span.appendChild(node);
                    } else {
                        var text = node.textContent;
                        var before = text.substring(0, start);
                        var mid = text.substring(start, end);
                        var after = text.substring(end);
                        var parent = node.parentNode;
                        
                        if (before.length > 0) parent.insertBefore(document.createTextNode(before), node);
                        
                        var span = document.createElement('span');
                        span.className = 'highlight-span';
                        span.textContent = mid;
                        if (dataStr) span.dataset.range = dataStr;
                        parent.insertBefore(span, node);
                        
                        if (after.length > 0) parent.insertBefore(document.createTextNode(after), node);
                        
                        parent.removeChild(node);
                    }
                });
            }

            // 4. Restore Highlights on Page Render
            window.PDFViewerApplication.eventBus.on('textlayerrendered', function(evt) {
                var pageNumber = evt.pageNumber;
                var pageDiv = evt.source.div;
                var textLayer = pageDiv.querySelector('.textLayer');
                if (!textLayer) return;

                var highlights = window.savedHighlights.filter(h => h.page === pageNumber);
                if (highlights.length === 0) return;

                highlights.forEach(function(h) {
                    try {
                        var rangeData = JSON.parse(h.rangeData);
                        if (rangeData.start !== undefined && rangeData.end !== undefined) {
                            var range = restoreRangeFromOffset(textLayer, rangeData.start, rangeData.end);
                            if (range) {
                                highlightRange(range, 'rgba(255, 235, 59, 0.5)', h.rangeData);
                            }
                        }
                    } catch(e) { console.error('Restore error', e); }
                });
            });

            // --- Helper Functions for Robust Persistence (Character Offsets) ---

            function getRangeOffsets(range, root) {
                var start = 0;
                var end = 0;
                var foundStart = false;
                var foundEnd = false;
                
                var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null, false);
                var node;
                var currentOffset = 0;

                while ((node = walker.nextNode())) {
                    var len = node.textContent.length;
                    
                    if (!foundStart && node === range.startContainer) {
                        start = currentOffset + range.startOffset;
                        foundStart = true;
                    }
                    
                    if (!foundEnd && node === range.endContainer) {
                        end = currentOffset + range.endOffset;
                        foundEnd = true;
                    }
                    
                    currentOffset += len;
                    if (foundStart && foundEnd) break;
                }
                return { start: start, end: end };
            }

            function restoreRangeFromOffset(root, start, end) {
                var range = document.createRange();
                var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null, false);
                var node;
                var currentOffset = 0;
                var startNode = null;
                var endNode = null;

                while ((node = walker.nextNode())) {
                    var len = node.textContent.length;
                    
                    // Find Start
                    if (!startNode && start >= currentOffset && start < currentOffset + len) {
                        range.setStart(node, start - currentOffset);
                        startNode = node;
                    }
                    
                    // Find End
                    if (!endNode && end >= currentOffset && end <= currentOffset + len) {
                        range.setEnd(node, end - currentOffset);
                        endNode = node;
                    }
                    
                    currentOffset += len;
                    if (startNode && endNode) break;
                }
                
                if (startNode && endNode) return range;
                return null;
            }

            // 5. Helper to notify Android of page changes
            window.PDFViewerApplication.eventBus.on('pagechanging', function(evt) {
                Android.onPageChanged(evt.pageNumber, window.PDFViewerApplication.pagesCount);
            });

            // 6. Custom Context Menu Logic
            var menu = document.createElement('div');
            menu.id = 'custom-context-menu';
            menu.style.position = 'fixed';
            menu.style.zIndex = '10000';
            menu.style.background = 'white';
            menu.style.border = '1px solid #ccc';
            menu.style.borderRadius = '8px';
            menu.style.boxShadow = '0 4px 12px rgba(0,0,0,0.15)';
            menu.style.display = 'none';
            menu.style.padding = '8px';
            menu.style.display = 'flex';
            menu.style.gap = '8px';
            
            var isActionProcessing = false;

            function createMenuButton(text, onClick) {
                var btn = document.createElement('button');
                btn.innerText = text;
                btn.style.background = 'transparent';
                btn.style.border = 'none';
                btn.style.padding = '8px 12px';
                btn.style.fontSize = '14px';
                btn.style.fontWeight = '500';
                btn.style.color = '#333';
                btn.style.cursor = 'pointer';
                btn.onclick = function(e) {
                    e.stopPropagation();
                    e.preventDefault();
                    isActionProcessing = true;
                    onClick();
                    menu.style.display = 'none';
                    setTimeout(function() { isActionProcessing = false; }, 500);
                };
                return btn;
            }
            
            var btnDefine = createMenuButton('Define', function() {
                Android.onDefine(window.getSelection().toString());
            });
            
            var btnHighlight = createMenuButton('Highlight', function() {
                var selection = window.getSelection();
                var text = selection.toString();
                if (text.length > 0) {
                    try {
                        var range = selection.getRangeAt(0);
                        
                        // Calculate Offsets for Persistence
                        var pageDiv = range.commonAncestorContainer;
                        while(pageDiv && !pageDiv.classList.contains('page')) {
                            pageDiv = pageDiv.parentElement;
                        }
                        var textLayer = pageDiv ? pageDiv.querySelector('.textLayer') : null;
                        
                        var rangeDataStr = '{}';
                        if (textLayer) {
                            var offsets = getRangeOffsets(range, textLayer);
                            rangeDataStr = JSON.stringify(offsets);
                        }

                        // Apply Visual Highlight
                        highlightRange(range, 'rgba(255, 235, 59, 0.5)', rangeDataStr);
                        
                        // Save
                        Android.onHighlight(text, window.PDFViewerApplication.page, rangeDataStr);
                        selection.removeAllRanges();
                    } catch(e) { console.error(e); }
                }
            });

            var btnRemoveHighlight = createMenuButton('Remove Highlight', function() {
                 var selection = window.getSelection();
                 var text = selection.toString();
                 
                 // Find the rangeData from the highlighted span
                 var range = selection.getRangeAt(0);
                 var parent = range.commonAncestorContainer.parentElement;
                 var rangeData = '{}';
                 if (parent && parent.classList.contains('highlight-span') && parent.dataset.range) {
                     rangeData = parent.dataset.range;
                 }
                 
                 Android.onRemoveHighlight(text, window.PDFViewerApplication.page, rangeData);
                 selection.removeAllRanges();
            });
            
            var btnCopy = createMenuButton('Copy', function() {
                Android.onCopy(window.getSelection().toString());
            });
            
            menu.appendChild(btnDefine);
            menu.appendChild(btnHighlight); 
            menu.appendChild(btnCopy);
            
            document.body.appendChild(menu);
            
            // Improved Selection Logic: Show menu only on interaction end
            var isSelecting = false;

            document.addEventListener('selectionchange', function() {
                if (isActionProcessing) return;
                // Hide menu while selecting to avoid interference with drag
                menu.style.display = 'none';
                isSelecting = true;
            });

            function handleSelectionEnd(e) {
                if (isActionProcessing) return;
                isSelecting = false;
                
                setTimeout(function() {
                    var selection = window.getSelection();
                    if (selection.toString().trim().length > 0) {
                        var range = selection.getRangeAt(0);
                        var rect = range.getBoundingClientRect();
                        
                        // Check if selection is already highlighted
                        var parent = range.commonAncestorContainer.parentElement;
                        var isHighlighted = parent && parent.classList.contains('highlight-span');
                        
                        menu.innerHTML = '';
                        menu.appendChild(btnDefine);
                        if (isHighlighted) {
                            menu.appendChild(btnRemoveHighlight);
                        } else {
                            menu.appendChild(btnHighlight);
                        }
                        menu.appendChild(btnCopy);
                        
                        var top = rect.bottom + 10;
                        if (top + 50 > window.innerHeight) {
                            top = rect.top - 50;
                        }
                        
                        menu.style.top = top + 'px';
                        menu.style.left = Math.max(10, Math.min(window.innerWidth - 250, rect.left)) + 'px';
                        menu.style.display = 'flex';
                    }
                }, 100); // Small delay to let selection settle
            }

            document.addEventListener('mouseup', handleSelectionEnd);
            document.addEventListener('touchend', handleSelectionEnd);
            
            document.addEventListener('click', function(e) {
                if (window.getSelection().toString().length === 0) {
                    if (!menu.contains(e.target)) {
                        Android.onToggleControls();
                    }
                }
            });
            
            document.addEventListener('click', function(e) {
                if (window.getSelection().toString().length === 0) {
                    if (!menu.contains(e.target)) {
                        Android.onToggleControls();
                    }
                }
            });
        """
        binding.webview.evaluateJavascript(js, null)
    }

    private fun loadPdf(path: String) {
        binding.progressBar.visibility = View.VISIBLE
        try {
            // Load the viewer from the same "virtual" domain to avoid CORS issues
            val encodedUrl = URLEncoder.encode("https://etab.local/book.pdf", "UTF-8")
            val viewerUrl = "https://etab.local/pdfjs/web/viewer.html?file=$encodedUrl"
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
                rangeData = rangeData,
                text = text
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
            binding.textPageIndicator.text = "Page $pageNumber of $totalPages"
            binding.pageSlider.max = totalPages - 1
            binding.pageSlider.progress = pageNumber - 1
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

    private val hideControlsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideControlsRunnable = Runnable {
        binding.zoomControls.animate().alpha(0f).setDuration(300).start()
        binding.bottomControls.animate().alpha(0f).setDuration(300).start()
    }

    private fun showControlsTemporarily() {
        binding.zoomControls.alpha = 1f
        binding.zoomControls.visibility = View.VISIBLE
        binding.bottomControls.alpha = 1f
        binding.bottomControls.visibility = View.VISIBLE

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

    override fun onPause() {
        super.onPause()
        if (isSplitView) {
            val fragment = supportFragmentManager.findFragmentById(R.id.whiteboard_container) as? org.weproz.etab.ui.notes.whiteboard.WhiteboardFragment
            fragment?.saveWhiteboard()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
    }
}
