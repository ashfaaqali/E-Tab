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
            // 1. Hide Default Toolbar & Custom CSS
            var style = document.createElement('style');
            style.innerHTML = `
                .toolbar { display: none !important; } 
                #viewerContainer { top: 0 !important; } 
                .highlight-span { background-color: rgba(255, 235, 59, 0.5); cursor: pointer; }
                #custom-menu {
                    position: absolute;
                    background: #333;
                    color: white;
                    padding: 8px;
                    border-radius: 4px;
                    display: none;
                    z-index: 10000;
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
            `;
            document.head.appendChild(style);

            // 2. Menu HTML
            if (!document.getElementById('custom-menu')) {
                var menuHtml = `
                    <div id="custom-menu">
                        <button id="btn-define" onclick="defineWord()">Define</button>
                        <div class="separator"></div>
                        <button id="btn-highlight" onclick="highlightText()">Highlight</button>
                        <div class="separator"></div>
                        <button id="btn-copy" onclick="copyText()">Copy</button>
                        <button id="btn-remove" onclick="removeHighlight()" style="display:none">Remove Highlight</button>
                    </div>
                `;
                document.body.insertAdjacentHTML('beforeend', menuHtml);
            }

            // 3. Global Highlights Store
            window.savedHighlights = [];
            window.restoreHighlights = function(data) {
                window.savedHighlights = data;
            };

            // 4. Highlight Logic
            function highlightRange(range, color, dataStr) {
                var nodes = [];
                
                // Optimization for single text node selection (common for single word/line)
                if (range.startContainer === range.endContainer && range.startContainer.nodeType === 3) {
                    nodes.push(range.startContainer);
                } else {
                    var root = range.commonAncestorContainer;
                    // Ensure root is an element, not a text node
                    while (root.nodeType === 3) {
                        root = root.parentNode;
                    }
                    
                    var nodeIterator = document.createNodeIterator(
                        root,
                        NodeFilter.SHOW_TEXT,
                        {
                            acceptNode: function(node) {
                                if (!range.intersectsNode(node)) return NodeFilter.FILTER_REJECT;
                                return NodeFilter.FILTER_ACCEPT;
                            }
                        }
                    );

                    var node;
                    while ((node = nodeIterator.nextNode())) {
                        nodes.push(node);
                    }
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
                    
                    if (!startNode && start >= currentOffset && start < currentOffset + len) {
                        range.setStart(node, start - currentOffset);
                        startNode = node;
                    }
                    
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

            // 5. Interaction Logic
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
                    
                    var isHighlighted = false;
                    var parent = selectionRange.commonAncestorContainer;
                    if (parent.nodeType === 3) parent = parent.parentNode;
                    
                    if (parent.classList.contains('highlight-span')) {
                        isHighlighted = true;
                        clickedHighlight = parent;
                    }
                    
                    if (!isHighlighted) {
                         var highlights = document.querySelectorAll('.highlight-span');
                         for(var i=0; i<highlights.length; i++) {
                             if (selection.containsNode(highlights[i], true)) {
                                 isHighlighted = true;
                                 break;
                             }
                         }
                    }

                    if (isHighlighted) {
                        document.getElementById('btn-define').style.display = 'inline-block';
                        document.getElementById('btn-highlight').style.display = 'none';
                        document.getElementById('btn-copy').style.display = 'inline-block';
                        document.getElementById('btn-remove').style.display = 'inline-block';
                    } else {
                        resetMenuButtons();
                    }
                    
                    var rect = selectionRange.getBoundingClientRect();
                    var scrollTop = window.scrollY || document.documentElement.scrollTop;
                    var scrollLeft = window.scrollX || document.documentElement.scrollLeft;
                    
                    menu.style.display = 'block';
                    var menuWidth = menu.offsetWidth || 200;
                    var menuHeight = menu.offsetHeight || 40;
                    
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
                
                if (menu.contains(e.target)) return;

                if (e.target.classList.contains('highlight-span')) {
                    clickedHighlight = e.target;
                    selectedText = e.target.textContent;
                    
                    document.getElementById('btn-define').style.display = 'inline-block';
                    document.getElementById('btn-highlight').style.display = 'none';
                    document.getElementById('btn-copy').style.display = 'inline-block';
                    document.getElementById('btn-remove').style.display = 'inline-block';
                    
                    menu.style.display = 'block';
                    
                    var rect = e.target.getBoundingClientRect();
                    var scrollTop = window.scrollY || document.documentElement.scrollTop;
                    var scrollLeft = window.scrollX || document.documentElement.scrollLeft;
                    
                    var menuWidth = menu.offsetWidth || 200;
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

                if (menu.style.display === 'block') {
                    menu.style.display = 'none';
                    resetMenuButtons();
                    return;
                }

                if (window.getSelection().toString().length > 0) return;
                
                var width = window.innerWidth;
                var x = e.clientX;
                
                if (x < width * 0.25) {
                    Android.onPrevPage();
                } else if (x > width * 0.75) {
                    Android.onNextPage();
                } else {
                    Android.onToggleControls();
                }
            });

            window.defineWord = function() {
                Android.onDefine(selectedText.trim());
                document.getElementById('custom-menu').style.display = 'none';
            };

            window.copyText = function() {
                Android.onCopy(selectedText);
                document.getElementById('custom-menu').style.display = 'none';
            };

            window.highlightText = function() {
                if (selectionRange) {
                    var pageDiv = selectionRange.commonAncestorContainer;
                    // Fix: Handle Text Node start
                    if (pageDiv.nodeType === 3) {
                        pageDiv = pageDiv.parentNode;
                    }
                    
                    while(pageDiv && !pageDiv.classList.contains('page')) {
                        pageDiv = pageDiv.parentElement;
                    }
                    var textLayer = pageDiv ? pageDiv.querySelector('.textLayer') : null;
                    
                    var rangeDataStr = '{}';
                    if (textLayer) {
                        var offsets = getRangeOffsets(selectionRange, textLayer);
                        rangeDataStr = JSON.stringify(offsets);
                    }

                    highlightRange(selectionRange, 'rgba(255, 235, 59, 0.5)', rangeDataStr);
                    Android.onHighlight(selectedText, window.PDFViewerApplication.page, rangeDataStr);
                    
                    window.getSelection().removeAllRanges();
                    document.getElementById('custom-menu').style.display = 'none';
                }
            };

            window.removeHighlight = function() {
                var selection = window.getSelection();
                if (selection.rangeCount > 0 && !selection.getRangeAt(0).collapsed) {
                    var range = selection.getRangeAt(0);
                    var highlights = document.querySelectorAll('.highlight-span');
                    
                    highlights.forEach(function(el) {
                        if (range.intersectsNode(el)) {
                            var text = el.textContent;
                            var oldRangeData = el.dataset.range;
                            
                            Android.onRemoveHighlight(text, window.PDFViewerApplication.page, oldRangeData);
                            
                            var elRange = document.createRange();
                            elRange.selectNodeContents(el);
                            
                            var startOffset = 0;
                            var endOffset = text.length;
                            
                            if (range.compareBoundaryPoints(Range.START_TO_START, elRange) > 0) {
                                startOffset = range.startOffset;
                            }
                            if (range.compareBoundaryPoints(Range.END_TO_END, elRange) < 0) {
                                endOffset = range.endOffset;
                            }
                            
                            var parent = el.parentNode;
                            var fragment = document.createDocumentFragment();
                            
                            var pageDiv = parent; 
                            while(pageDiv && !pageDiv.classList.contains('page')) pageDiv = pageDiv.parentElement;
                            var textLayer = pageDiv ? pageDiv.querySelector('.textLayer') : null;

                            if (startOffset > 0) {
                                var span1 = document.createElement('span');
                                span1.className = 'highlight-span';
                                span1.textContent = text.substring(0, startOffset);
                                fragment.appendChild(span1);
                                
                                if (textLayer) {
                                    span1.dataset.needsIndex = 'true';
                                }
                            }
                            
                            fragment.appendChild(document.createTextNode(text.substring(startOffset, endOffset)));
                            
                            if (endOffset < text.length) {
                                var span2 = document.createElement('span');
                                span2.className = 'highlight-span';
                                span2.textContent = text.substring(endOffset);
                                span2.dataset.needsIndex = 'true';
                                fragment.appendChild(span2);
                            }
                            
                            parent.replaceChild(fragment, el);
                            
                            if (textLayer) {
                                var newSpans = parent.querySelectorAll('span[data-needs-index="true"]');
                                newSpans.forEach(function(span) {
                                    span.removeAttribute('data-needs-index');
                                    var r = document.createRange();
                                    r.selectNodeContents(span);
                                    var offsets = getRangeOffsets(r, textLayer);
                                    var rData = JSON.stringify(offsets);
                                    span.dataset.range = rData;
                                    Android.onHighlight(span.textContent, window.PDFViewerApplication.page, rData);
                                });
                            }
                        }
                    });
                    
                    selection.removeAllRanges();
                } else if (clickedHighlight) {
                    var text = clickedHighlight.textContent;
                    var rangeData = clickedHighlight.dataset.range;
                    Android.onRemoveHighlight(text, window.PDFViewerApplication.page, rangeData);
                    
                    var parent = clickedHighlight.parentNode;
                    parent.replaceChild(document.createTextNode(text), clickedHighlight);
                    parent.normalize();
                    clickedHighlight = null;
                }
                
                document.getElementById('custom-menu').style.display = 'none';
                resetMenuButtons();
            };
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
