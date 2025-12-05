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

        bookPath = intent.getStringExtra("book_path")

        setupWebView()

        if (bookPath != null) {
            loadBook(bookPath!!)
        } else {
            Toast.makeText(this, "Error loading book", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnPrev.setOnClickListener {
            if (currentChapterIndex > 0) {
                displayChapter(currentChapterIndex - 1)
            }
        }

        binding.btnNext.setOnClickListener {
            currentBook?.let { book ->
                if (currentChapterIndex < book.spine.size() - 1) {
                    displayChapter(currentChapterIndex + 1)
                }
            }
        }
    }

    private fun setupWebView() {
        binding.webview.settings.javaScriptEnabled = true
        binding.webview.settings.domStorageEnabled = true
        binding.webview.addJavascriptInterface(WebAppInterface(), "Android")
        binding.webview.webChromeClient = WebChromeClient()
        binding.webview.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progressBar.visibility = View.GONE
                restoreHighlights()
            }

            override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                android.util.Log.d("ReaderActivity", "Intercepting request: $url")
                
                if (url.startsWith("file:///book_res/")) {
                    // Try to find resource in book
                    // We iterate to find a matching href ending.
                    // Normalize url to remove the fake base
                    val relativePath = url.removePrefix("file:///book_res/")
                    
                    currentBook?.resources?.all?.forEach { resource ->
                        // Check if the resource href matches the end of the requested URL
                        // This handles cases where href might be "OEBPS/images/img.jpg" and request is ".../images/img.jpg"
                        if (url.endsWith(resource.href) || resource.href.endsWith(relativePath)) {
                             android.util.Log.d("ReaderActivity", "Found resource: ${resource.href}")
                            return android.webkit.WebResourceResponse(
                                resource.mediaType.name,
                                "UTF-8",
                                resource.inputStream
                            )
                        }
                    }
                    android.util.Log.w("ReaderActivity", "Resource not found for: $url")
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
                android.util.Log.d("ReaderActivity", "Raw content length: ${rawContent.length}")
                
                // Inject JS and CSS
                val content = injectCustomContent(rawContent)
                
                // Use a fake base URL that we can intercept
                // We append the resource href to help with relative path resolution if needed, 
                // but for now just using a root base.
                val baseUrl = "file:///book_res/" + resource.href
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
                    // Simple restore by finding text and wrapping it.
                    // This is naive and will replace all occurrences.
                    // A better way is to use the stored range data.
                    var body = document.body.innerHTML;
                    var newBody = body.replace(new RegExp(text, 'g'), '<span class="highlighted">' + text + '</span>');
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
                    binding.webview.evaluateJavascript("restoreHighlight('${highlight.highlightedText}')", null)
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
