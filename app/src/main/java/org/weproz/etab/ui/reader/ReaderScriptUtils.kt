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
}
