package org.weproz.etab.ui.reader

object ReaderScriptUtils {
    const val PDF_JS_INJECTION = """
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
                        // Only send if valid dimensions
                        if (rect.width > 0 && rect.height > 0) {
                            Android.onPageBounds(rect.left, rect.top, rect.right, rect.bottom);
                        }
                    }
                }

                window.PDFViewerApplication.eventBus.on('pagesinit', function() {
                    // Force Page Scroll Mode (3) to disable continuous scrolling
                    if (window.PDFViewerApplication.pdfViewer) {
                        window.PDFViewerApplication.pdfViewer.scrollMode = 3;
                        window.PDFViewerApplication.pdfViewer.currentScaleValue = 'page-fit';
                    }
                    updatePage(window.PDFViewerApplication.page, window.PDFViewerApplication.pagesCount);
                });
                
                window.PDFViewerApplication.eventBus.on('pagechanging', function(evt) {
                    updatePage(evt.pageNumber, window.PDFViewerApplication.pagesCount);
                });
                
                window.PDFViewerApplication.eventBus.on('pagerendered', function(evt) {
                    if (evt.pageNumber === window.PDFViewerApplication.page) {
                        updatePageBounds();
                    }
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
                
                // Only toggle controls, no navigation on tap
                Android.onToggleControls();
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

            // Sync Scroll for PDF.js
            function setupSync() {
                var scrollContainer = document.getElementById('viewerContainer');
                
                // Fallback: Find the main scrolling element if viewerContainer is not it
                if (!scrollContainer || getComputedStyle(scrollContainer).overflow === 'hidden') {
                     // Try to find an element with overflow auto/scroll
                     var candidates = document.querySelectorAll('div');
                     for (var i = 0; i < candidates.length; i++) {
                         var style = getComputedStyle(candidates[i]);
                         if ((style.overflow === 'auto' || style.overflow === 'scroll' || style.overflowY === 'auto' || style.overflowY === 'scroll') && candidates[i].scrollHeight > candidates[i].clientHeight) {
                             scrollContainer = candidates[i];
                             break;
                         }
                     }
                }
                
                // Fallback to window if no container found
                var useWindow = !scrollContainer;

                if (!scrollContainer && !document.body) {
                    setTimeout(setupSync, 500); // Retry if DOM not ready
                    return;
                }

                var lastX = -1, lastY = -1, lastScale = -1, lastPage = -1;
                function sync() {
                    var x = useWindow ? (window.scrollX || window.pageXOffset) : scrollContainer.scrollLeft;
                    var y = useWindow ? (window.scrollY || window.pageYOffset) : scrollContainer.scrollTop;
                    
                    // Check visual viewport for pinch-zoom offset
                    if (window.visualViewport) {
                        x += window.visualViewport.offsetLeft;
                        y += window.visualViewport.offsetTop;
                    }

                    var scale = (window.PDFViewerApplication && window.PDFViewerApplication.pdfViewer) ? window.PDFViewerApplication.pdfViewer.currentScale : 1.0;
                    var page = (window.PDFViewerApplication) ? window.PDFViewerApplication.page : 1;
                    
                    // Send raw values, let Android handle density
                    if (Math.abs(x - lastX) > 1 || Math.abs(y - lastY) > 1 || Math.abs(scale - lastScale) > 0.01 || page !== lastPage) {
                        lastX = x; lastY = y; lastScale = scale; lastPage = page;
                        if (window.Android && window.Android.onSyncScroll) {
                            window.Android.onSyncScroll(x, y, scale, page);
                        }
                        // Also update bounds on scroll/zoom
                        if (typeof updatePageBounds === 'function') {
                            updatePageBounds();
                        }
                    }
                }
                
                if (scrollContainer) {
                    scrollContainer.addEventListener('scroll', sync);
                }
                window.addEventListener('scroll', sync, true); // Capture phase to catch all
                
                if (window.visualViewport) {
                    window.visualViewport.addEventListener('scroll', sync);
                    window.visualViewport.addEventListener('resize', sync);
                }
                
                document.addEventListener('webviewerzoomchanged', function(e) {
                    sync();
                });
                
                // Initial sync
                setTimeout(sync, 100);
                setInterval(sync, 100); // Polling fallback (faster)
            }
            setupSync();
    """

    const val EPUB_JS_INJECTION = """
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

                // --- Global Offset Logic ---

                function getGlobalOffset(node, offset) {
                    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                    var currentOffset = 0;
                    while(walker.nextNode()) {
                        if (walker.currentNode === node) {
                            return currentOffset + offset;
                        }
                        currentOffset += walker.currentNode.length;
                    }
                    return -1;
                }

                function createRangeFromOffsets(start, end) {
                    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                    var currentOffset = 0;
                    var range = document.createRange();
                    var startFound = false, endFound = false;

                    while(walker.nextNode()) {
                        var node = walker.currentNode;
                        var nodeLength = node.length;

                        if (!startFound && start >= currentOffset && start < currentOffset + nodeLength) {
                            range.setStart(node, start - currentOffset);
                            startFound = true;
                        }

                        if (!endFound && end > currentOffset && end <= currentOffset + nodeLength) {
                            range.setEnd(node, end - currentOffset);
                            endFound = true;
                        }

                        if (startFound && endFound) return range;
                        currentOffset += nodeLength;
                    }
                    return null;
                }

                // --- Interaction Logic ---

                document.addEventListener('selectionchange', function() {
                    var selection = window.getSelection();
                    var menu = document.getElementById('custom-menu');
                    
                    if (selection.toString().length > 0) {
                        selectedText = selection.toString();
                        selectionRange = selection.getRangeAt(0);
                        
                        var isHighlighted = false;
                        
                        // Check ancestor
                        var parent = selectionRange.commonAncestorContainer;
                        if (parent.nodeType === 3) parent = parent.parentNode;
                        if (parent.classList.contains('highlighted')) {
                            isHighlighted = true;
                            clickedHighlight = parent;
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

                    if (e.target.classList.contains('highlighted')) {
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
                    
                    // Only toggle controls, no navigation on tap
                    Android.onToggleControls();
                });

                function defineWord() {
                    Android.onDefine(selectedText.trim());
                    document.getElementById('custom-menu').style.display = 'none';
                }

                function copyText() {
                    Android.onCopy(selectedText);
                    document.getElementById('custom-menu').style.display = 'none';
                }

                function applyHighlightToRange(range, rangeData) {
                    var startNode = range.startContainer;
                    var startOffset = range.startOffset;
                    var endNode = range.endContainer;
                    var endOffset = range.endOffset;

                    // Collect all text nodes in range
                    var nodes = [];
                    
                    if (startNode === endNode && startNode.nodeType === 3) {
                        nodes.push(startNode);
                    } else {
                        var commonAncestor = range.commonAncestorContainer;
                        var walker = document.createTreeWalker(commonAncestor, NodeFilter.SHOW_TEXT, {
                            acceptNode: function(node) {
                                if (range.intersectsNode(node)) return NodeFilter.FILTER_ACCEPT;
                                return NodeFilter.FILTER_REJECT;
                            }
                        }, false);
                        while(walker.nextNode()) nodes.push(walker.currentNode);
                    }

                    nodes.forEach(function(node) {
                        var r = document.createRange();
                        if (node === startNode && node === endNode) {
                            r.setStart(node, startOffset);
                            r.setEnd(node, endOffset);
                        } else if (node === startNode) {
                            r.setStart(node, startOffset);
                            r.setEnd(node, node.length);
                        } else if (node === endNode) {
                            r.setStart(node, 0);
                            r.setEnd(node, endOffset);
                        } else {
                            r.selectNodeContents(node);
                        }
                        
                        if (!r.collapsed) {
                            var span = document.createElement('span');
                            span.className = 'highlighted';
                            span.dataset.range = rangeData;
                            try {
                                r.surroundContents(span);
                            } catch(e) {
                                console.error("Highlight error", e);
                            }
                        }
                    });
                }

                function highlightText() {
                    if (selectionRange) {
                        var start = getGlobalOffset(selectionRange.startContainer, selectionRange.startOffset);
                        var end = getGlobalOffset(selectionRange.endContainer, selectionRange.endOffset);
                        
                        if (start !== -1 && end !== -1) {
                            var rangeData = JSON.stringify({start: start, end: end});
                            applyHighlightToRange(selectionRange, rangeData);
                            Android.onHighlight(selectedText, rangeData);
                        }
                        
                        window.getSelection().removeAllRanges();
                        document.getElementById('custom-menu').style.display = 'none';
                    }
                }

                function restoreHighlight(text, rangeDataStr) {
                    try {
                        var data = JSON.parse(rangeDataStr);
                        if (typeof data.start === 'number' && typeof data.end === 'number') {
                            var range = createRangeFromOffsets(data.start, data.end);
                            if (range) {
                                applyHighlightToRange(range, rangeDataStr);
                            }
                        }
                    } catch(e) {
                        console.error("Restore error", e);
                    }
                }

                function removeHighlight() {
                    var rangeData = null;
                    var text = "";
                    
                    if (clickedHighlight) {
                        rangeData = clickedHighlight.dataset.range;
                        text = clickedHighlight.textContent;
                    } else if (selectionRange) {
                        var parent = selectionRange.commonAncestorContainer;
                        if (parent.nodeType === 3) parent = parent.parentNode;
                        if (parent.classList.contains('highlighted')) {
                            rangeData = parent.dataset.range;
                            text = parent.textContent;
                        }
                    }
                    
                    if (rangeData) {
                        var spans = document.querySelectorAll('.highlighted');
                        spans.forEach(function(span) {
                            if (span.dataset.range === rangeData) {
                                var parent = span.parentNode;
                                while(span.firstChild) {
                                    parent.insertBefore(span.firstChild, span);
                                }
                                parent.removeChild(span);
                                parent.normalize();
                            }
                        });
                        
                        Android.onRemoveHighlight(text, rangeData);
                    }
                    
                    document.getElementById('custom-menu').style.display = 'none';
                    resetMenuButtons();
                    clickedHighlight = null;
                }

            // Sync Scroll for EPUB
            var lastX = -1, lastY = -1, lastScale = -1;
            function sync() {
                var x = window.scrollX || window.pageXOffset || document.documentElement.scrollLeft;
                var y = window.scrollY || window.pageYOffset || document.documentElement.scrollTop;
                var scale = window.visualViewport ? window.visualViewport.scale : 1.0;
                
                if (Math.abs(x - lastX) > 1 || Math.abs(y - lastY) > 1 || Math.abs(scale - lastScale) > 0.01) {
                    lastX = x; lastY = y; lastScale = scale;
                    if (window.Android && window.Android.onSyncScroll) {
                        window.Android.onSyncScroll(x, y, scale);
                    }
                }
            }
            window.addEventListener('scroll', sync);
            window.addEventListener('resize', sync);
            if (window.visualViewport) {
                window.visualViewport.addEventListener('resize', sync);
                window.visualViewport.addEventListener('scroll', sync);
            }
            // Initial sync
            setTimeout(sync, 500);
            setInterval(sync, 100); // Polling fallback (faster)
            
            // Poll for page bounds as well to ensure clipping is correct
            setInterval(function() {
                if (typeof updatePageBounds === 'function') {
                    updatePageBounds();
                }
            }, 200);
            </script>
    """
}
