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
import android.widget.PopupWindow
import android.view.LayoutInflater
import android.view.ViewGroup
import android.graphics.Color
import org.weproz.etab.ui.custom.CustomDialog
import org.weproz.etab.ui.notes.whiteboard.WhiteboardView
import org.weproz.etab.ui.notes.whiteboard.DrawAction
import org.weproz.etab.ui.notes.whiteboard.WhiteboardSerializer
import org.weproz.etab.ui.notes.whiteboard.ParsedPage
import org.weproz.etab.ui.notes.whiteboard.GridType

class PdfReaderActivity : AppCompatActivity(), PdfReaderBridge {

    private lateinit var binding: ActivityPdfReaderBinding
    private var pdfPath: String? = null
    private var isSplitView = false
    
    // Annotation Persistence
    private val pageAnnotations = mutableMapOf<Int, List<DrawAction>>()
    private var currentPage = 1
    private var totalPages = 0

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

            // 5. Helper to notify Android of page changes
            function setupPageListeners() {
                if (!window.PDFViewerApplication || !window.PDFViewerApplication.eventBus) {
                    setTimeout(setupPageListeners, 200);
                    return;
                }
                
                function updatePage(page, total) {
                    Android.onPageChanged(page, total);
                    updatePageBounds();
                }
                
                function updatePageBounds() {
                    // Get the visible page container
                    var viewer = document.getElementById('viewer');
                    if (!viewer) return;
                    
                    var pageIndex = window.PDFViewerApplication.page - 1;
                    var pageView = window.PDFViewerApplication.pdfViewer.getPageView(pageIndex);
                    
                    if (pageView && pageView.div) {
                        var rect = pageView.div.getBoundingClientRect();
                        // Send bounds relative to viewport
                        // Note: getBoundingClientRect returns values in CSS pixels.
                        // Android WebView density scaling handles the conversion if we use density-independent pixels on Android side?
                        // Actually, WebView.getScale() is deprecated.
                        // The values from JS are in CSS pixels.
                        // Android side needs to know the density to convert if the WebView is scaled.
                        // But wait, WebView handles the scaling. 
                        // If I send CSS pixels, and on Android side I multiply by density, it should match the view coordinates.
                        Android.onPageBounds(rect.left, rect.top, rect.right, rect.bottom);
                    }
                }

                window.PDFViewerApplication.eventBus.on('pagesinit', function() {
                    updatePage(window.PDFViewerApplication.page, window.PDFViewerApplication.pagesCount);
                });
                
                window.PDFViewerApplication.eventBus.on('pagechanging', function(evt) {
                    updatePage(evt.pageNumber, window.PDFViewerApplication.pagesCount);
                });
                
                window.PDFViewerApplication.eventBus.on('scalechanging', function(evt) {
                    setTimeout(updatePageBounds, 100); // Wait for render
                });
                
                // Also update on scroll
                window.addEventListener('scroll', function() {
                    updatePageBounds();
                }, true); // Capture phase to catch container scroll
                
                if (window.PDFViewerApplication.pagesCount > 0) {
                    updatePage(window.PDFViewerApplication.page, window.PDFViewerApplication.pagesCount);
                }
            }
            setupPageListeners();

            // 6. Interaction Logic
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
                    if (pageDiv.nodeType === 3) {
                        pageDiv = pageDiv.parentNode;
                    }
                    
                    // Find page element
                    var pageEl = pageDiv.closest('.page');
                    var pageNumber = pageEl ? parseInt(pageEl.getAttribute('data-page-number')) : window.PDFViewerApplication.page;
                    
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
                    Android.onHighlight(selectedText, pageNumber, rangeDataStr);
                    
                    window.getSelection().removeAllRanges();
                    document.getElementById('custom-menu').style.display = 'none';
                }
            };

            window.removeHighlight = function() {
                var selection = window.getSelection();
                
                // Helper to process a specific rangeData group
                function processHighlightGroup(rangeData, pageNumber, removalRange) {
                    // 1. Delete the logical highlight from DB
                    Android.onRemoveHighlight("", pageNumber, rangeData);
                    
                    // 2. Find all spans belonging to this logical highlight
                    var groupSpans = Array.from(document.querySelectorAll('.highlight-span')).filter(function(s) {
                        return s.dataset.range === rangeData;
                    });
                    
                    // 3. Process each span
                    groupSpans.forEach(function(el) {
                        var text = el.textContent;
                        var parent = el.parentNode;
                        
                        // Find textLayer for recalculating ranges
                        var pageDiv = parent; 
                        while(pageDiv && !pageDiv.classList.contains('page')) pageDiv = pageDiv.parentElement;
                        var textLayer = pageDiv ? pageDiv.querySelector('.textLayer') : null;
                        
                        // Check intersection with removal range (if partial)
                        var intersects = false;
                        if (removalRange) {
                            intersects = removalRange.intersectsNode(el);
                        } else {
                            intersects = true; // Full removal
                        }
                        
                        if (intersects && removalRange) {
                            // PARTIAL REMOVAL LOGIC
                            var elRange = document.createRange();
                            elRange.selectNodeContents(el);
                            
                            var startOffset = 0;
                            var endOffset = text.length;
                            
                            if (removalRange.compareBoundaryPoints(Range.START_TO_START, elRange) > 0) {
                                startOffset = removalRange.startOffset;
                            }
                            if (removalRange.compareBoundaryPoints(Range.END_TO_END, elRange) < 0) {
                                endOffset = removalRange.endOffset;
                            }
                            
                            var fragment = document.createDocumentFragment();
                            
                            // Keep Start
                            if (startOffset > 0) {
                                var span1 = document.createElement('span');
                                span1.className = 'highlight-span';
                                span1.textContent = text.substring(0, startOffset);
                                span1.dataset.needsIndex = 'true';
                                fragment.appendChild(span1);
                            }
                            
                            // Unhighlight Middle
                            fragment.appendChild(document.createTextNode(text.substring(startOffset, endOffset)));
                            
                            // Keep End
                            if (endOffset < text.length) {
                                var span2 = document.createElement('span');
                                span2.className = 'highlight-span';
                                span2.textContent = text.substring(endOffset);
                                span2.dataset.needsIndex = 'true';
                                fragment.appendChild(span2);
                            }
                            
                            parent.replaceChild(fragment, el);
                            
                            // Process any new spans created during partial removal
                            if (textLayer) {
                                var newSpans = parent.querySelectorAll('span[data-needs-index="true"]');
                                newSpans.forEach(function(span) {
                                    span.removeAttribute('data-needs-index');
                                    if (span.firstChild && span.firstChild.nodeType === 3) {
                                        var r = document.createRange();
                                        r.selectNodeContents(span.firstChild);
                                        var offsets = getRangeOffsets(r, textLayer);
                                        var rData = JSON.stringify(offsets);
                                        span.dataset.range = rData;
                                        Android.onHighlight(span.textContent, pageNumber, rData);
                                    }
                                });
                            }
                            
                        } else if (intersects && !removalRange) {
                            // FULL REMOVAL LOGIC
                            parent.replaceChild(document.createTextNode(text), el);
                            parent.normalize();
                        } else {
                            // NO INTERSECTION - PRESERVE AS NEW HIGHLIGHT
                            // This span was part of the old group but wasn't touched by the removal selection.
                            // Since we deleted the old group, we must save this span as a new independent highlight.
                            if (textLayer) {
                                // We can't just set needsIndex because it's already in the DOM.
                                // We need to calculate right now.
                                if (el.firstChild && el.firstChild.nodeType === 3) {
                                    var r = document.createRange();
                                    r.selectNodeContents(el.firstChild);
                                    var offsets = getRangeOffsets(r, textLayer);
                                    var rData = JSON.stringify(offsets);
                                    el.dataset.range = rData;
                                    Android.onHighlight(text, pageNumber, rData);
                                }
                            }
                        }
                    });
                }

                if (selection.rangeCount > 0 && !selection.getRangeAt(0).collapsed) {
                    var range = selection.getRangeAt(0);
                    var highlights = document.querySelectorAll('.highlight-span');
                    var processedRanges = new Set();
                    
                    highlights.forEach(function(el) {
                        if (range.intersectsNode(el)) {
                            var rangeData = el.dataset.range;
                            if (!processedRanges.has(rangeData)) {
                                processedRanges.add(rangeData);
                                var pageEl = el.closest('.page');
                                var pageNumber = pageEl ? parseInt(pageEl.getAttribute('data-page-number')) : window.PDFViewerApplication.page;
                                processHighlightGroup(rangeData, pageNumber, range);
                            }
                        }
                    });
                    
                    selection.removeAllRanges();
                } else if (clickedHighlight) {
                    var rangeData = clickedHighlight.dataset.range;
                    var pageEl = clickedHighlight.closest('.page');
                    var pageNumber = pageEl ? parseInt(pageEl.getAttribute('data-page-number')) : window.PDFViewerApplication.page;
                    
                    processHighlightGroup(rangeData, pageNumber, null);
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

        // Annotation Controls
        binding.annotationView.isTransparentBackground = true
        
        binding.btnAnnotateToggle.setOnClickListener {
            val isVisible = binding.layoutTools.visibility == View.VISIBLE
            if (isVisible) {
                // Hide tools, disable annotation
                binding.layoutTools.visibility = View.GONE
                binding.annotationView.visibility = View.GONE
                binding.btnAnnotateToggle.setColorFilter(Color.WHITE)
            } else {
                // Show tools, enable annotation
                binding.layoutTools.visibility = View.VISIBLE
                binding.annotationView.visibility = View.VISIBLE
                binding.btnAnnotateToggle.setColorFilter(Color.YELLOW) // Active indicator
                
                // Default tool
                updateActiveToolUI(binding.btnToolPen)
                binding.annotationView.setTool(WhiteboardView.ToolType.PEN)
            }
        }
        
        binding.btnToolPen.setOnClickListener {
            binding.annotationView.setTool(WhiteboardView.ToolType.PEN)
            updateActiveToolUI(it as android.widget.ImageButton)
            showPenSettingsPopup(it)
        }
        
        binding.btnToolEraser.setOnClickListener {
            binding.annotationView.setTool(WhiteboardView.ToolType.ERASER)
            updateActiveToolUI(it as android.widget.ImageButton)
            showEraserSettingsPopup(it)
        }
        
        binding.btnToolUndo.setOnClickListener { binding.annotationView.undo() }
        binding.btnToolRedo.setOnClickListener { binding.annotationView.redo() }
        binding.btnToolClear.setOnClickListener { showClearConfirmationDialog() }

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

    private fun updateActiveToolUI(activeButton: android.widget.ImageButton) {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typedValue, true)
        val backgroundResource = typedValue.resourceId
        
        binding.btnToolPen.setBackgroundResource(backgroundResource)
        binding.btnToolEraser.setBackgroundResource(backgroundResource)
        
        activeButton.setBackgroundResource(R.drawable.bg_toolbar_tool)
    }

    private fun showPenSettingsPopup(anchor: android.view.View) {
        val view = LayoutInflater.from(this).inflate(R.layout.popup_pen_settings, null)
        val popup = PopupWindow(view, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = 10f
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        
        val containerColors = view.findViewById<android.widget.LinearLayout>(R.id.container_colors)
        val seekSize = view.findViewById<android.widget.SeekBar>(R.id.seek_size)
        val groupType = view.findViewById<android.widget.RadioGroup>(R.id.group_pen_type)
        
        // Pen Type
        if (binding.annotationView.isHighlighter) {
            groupType.check(R.id.radio_highlighter)
        } else {
            groupType.check(R.id.radio_pen)
        }
        
        groupType.setOnCheckedChangeListener { _, checkedId ->
            binding.annotationView.isHighlighter = (checkedId == R.id.radio_highlighter)
        }
        
        seekSize.progress = binding.annotationView.getStrokeWidth().toInt()
        seekSize.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
             override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                 val size = progress.coerceAtLeast(1).toFloat()
                 binding.annotationView.setStrokeWidthGeneric(size)
             }
             override fun onStartTrackingTouch(seekBar: SeekBar?) {}
             override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Colors
        val colors = intArrayOf(Color.BLACK, Color.RED, Color.BLUE, Color.GREEN, Color.MAGENTA, Color.CYAN, Color.YELLOW)
        val currentColor = binding.annotationView.drawColor
        
        for (color in colors) {
             val colorView = View(this)
             val params = android.widget.LinearLayout.LayoutParams(60, 60)
             params.setMargins(8, 0, 8, 0)
             colorView.layoutParams = params
             
             val shape = android.graphics.drawable.GradientDrawable()
             shape.shape = android.graphics.drawable.GradientDrawable.OVAL
             shape.setColor(color)
             
             if (color == currentColor) {
                 shape.setStroke(6, Color.DKGRAY)
             } else {
                 shape.setStroke(2, Color.LTGRAY)
             }
             
             colorView.background = shape
             
             colorView.setOnClickListener {
                 binding.annotationView.drawColor = color
                 popup.dismiss()
             }
             containerColors.addView(colorView)
        }
        
        popup.showAsDropDown(anchor, 0, 10)
    }
    
    private fun showEraserSettingsPopup(anchor: android.view.View) {
        val view = LayoutInflater.from(this).inflate(R.layout.popup_eraser_settings, null)
        val popup = PopupWindow(view, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = 10f
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        
        val seekSize = view.findViewById<SeekBar>(R.id.seek_size)
        seekSize.progress = binding.annotationView.getStrokeWidth().toInt()
        
        seekSize.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
             override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                 val size = progress.coerceAtLeast(1).toFloat()
                 binding.annotationView.setStrokeWidthGeneric(size)
             }
             override fun onStartTrackingTouch(seekBar: SeekBar?) {}
             override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        popup.showAsDropDown(anchor, 0, 10)
    }

    private fun showClearConfirmationDialog() {
        CustomDialog(this)
            .setTitle("Clear Annotations")
            .setMessage("Are you sure you want to clear all annotations?")
            .setPositiveButton("Clear") { dialog ->
                binding.annotationView.clear()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel")
            .show()
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
        // Save previous page
        if (currentPage != pageNumber) {
             pageAnnotations[currentPage] = binding.annotationView.getPaths().toList()
        }
        
        this.currentPage = pageNumber
        this.totalPages = totalPages
        
        // Load new page
        val actions = pageAnnotations[pageNumber] ?: emptyList()
        binding.annotationView.loadPaths(actions)

        runOnUiThread {
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
            binding.annotationView.setClipBounds(rect)
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

    private fun getAnnotationsFile(): File {
        val fileName = File(pdfPath!!).name + ".annotations.json"
        return File(getExternalFilesDir("annotations"), fileName)
    }

    private fun loadAnnotations() {
        try {
            val file = getAnnotationsFile()
            if (file.exists()) {
                val json = file.readText()
                val data = WhiteboardSerializer.deserialize(json)
                
                pageAnnotations.clear()
                data.pages.forEachIndexed { index, page ->
                    pageAnnotations[index + 1] = page.actions
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveAnnotations() {
        try {
            // Save current page first
            pageAnnotations[currentPage] = binding.annotationView.getPaths().toList()
            
            val pages = mutableListOf<ParsedPage>()
            val maxPage = pageAnnotations.keys.maxOrNull() ?: 0
            
            for (i in 1..maxPage) {
                val actions = pageAnnotations[i] ?: emptyList()
                pages.add(ParsedPage(actions, GridType.NONE))
            }
            
            val json = WhiteboardSerializer.serialize(pages)
            val file = getAnnotationsFile()
            file.parentFile?.mkdirs()
            file.writeText(json)
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
    }
}
