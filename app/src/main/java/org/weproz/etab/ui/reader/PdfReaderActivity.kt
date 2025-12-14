package org.weproz.etab.ui.reader

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.weproz.etab.R
import org.weproz.etab.data.local.AppDatabase
import org.weproz.etab.databinding.ActivityPdfReaderBinding
import java.io.File

import android.graphics.RectF
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import org.weproz.etab.data.local.HighlightEntity
import org.weproz.etab.ui.search.DefinitionDialogFragment
import java.util.UUID

/**
 * Activity for viewing PDF files with a Google Drive-like experience.
 * Features:
 * - Pinch-to-zoom and pan gestures
 * - Page-by-page view with gaps between pages
 * - Zoom controls
 * - Page indicator
 * - Split view with whiteboard for notes
 * - Text selection and highlighting (via ML Kit)
 */
class PdfReaderActivity : AppCompatActivity(), PdfViewerView.OnHighlightActionListener {

    private lateinit var binding: ActivityPdfReaderBinding
    private var pdfPath: String? = null
    private var isSplitView = false
    private var popupWindow: PopupWindow? = null

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
            loadPdf(pdfPath!!)
            updateLastOpened(pdfPath!!)
            loadHighlights(pdfPath!!)
        } else {
            Toast.makeText(this, "Error loading PDF", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupControls()
        setupPageListener()
        
        binding.pdfViewer.onHighlightActionListener = this
    }

    private fun loadHighlights(path: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(this@PdfReaderActivity).highlightDao()
            val highlights = dao.getHighlightsForBook(path)
            withContext(Dispatchers.Main) {
                binding.pdfViewer.setHighlights(highlights)
            }
        }
    }

    private fun loadPdf(path: String) {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PdfReaderActivity, "PDF file not found", Toast.LENGTH_SHORT).show()
                    finish()
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                binding.pdfViewer.openPdf(file)
                binding.progressBar.visibility = View.GONE
                
                // Update page slider max
                val pageCount = binding.pdfViewer.getPageCount()
                binding.pageSlider.max = pageCount - 1
                updatePageIndicator(1, pageCount)
            }
        }
    }

    private fun updateLastOpened(path: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(this@PdfReaderActivity).bookDao()
            dao.updateLastOpened(path, System.currentTimeMillis())
        }
    }

    private fun setupControls() {
        // Back button
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Split view button
        binding.btnSplitView.setOnClickListener {
            toggleSplitView()
        }

        // Zoom controls
        binding.btnZoomIn.setOnClickListener {
            binding.pdfViewer.zoomIn()
            showControlsTemporarily()
        }

        binding.btnZoomOut.setOnClickListener {
            binding.pdfViewer.zoomOut()
            showControlsTemporarily()
        }

        binding.btnZoomReset.setOnClickListener {
            binding.pdfViewer.resetZoom()
            showControlsTemporarily()
        }

        // Page slider
        binding.pageSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.pdfViewer.goToPage(progress + 1)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                showControlsTemporarily()
            }
        })

        // Auto-hide controls
        showControlsTemporarily()
    }

    private fun setupPageListener() {
        binding.pdfViewer.onPageChangeListener = { currentPage, totalPages ->
            runOnUiThread {
                updatePageIndicator(currentPage, totalPages)
                binding.pageSlider.progress = currentPage - 1
            }
        }
        
        // Show controls when user taps on PDF
        binding.pdfViewer.onTapListener = {
            showControlsTemporarily()
        }
    }


    private fun updatePageIndicator(currentPage: Int, totalPages: Int) {
        binding.textPageIndicator.text = "Page $currentPage of $totalPages"
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
            // Save whiteboard before closing
            val fragment = supportFragmentManager.findFragmentById(R.id.whiteboard_container) as? org.weproz.etab.ui.notes.whiteboard.WhiteboardFragment
            fragment?.saveWhiteboard()

            // Restore full screen
            binding.whiteboardContainer.visibility = View.GONE
            binding.splitHandle.visibility = View.GONE

            val params = binding.pdfViewer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            params.topToBottom = R.id.top_action_bar
            params.height = 0
            binding.pdfViewer.layoutParams = params

            isSplitView = false
        } else {
            // Split screen mode - create a NEW whiteboard each time
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
        // Use timestamp to create unique file path for each session
        val timestamp = System.currentTimeMillis()
        val notesPath = File(getExternalFilesDir(null), "wb_pdf_$timestamp.json").absolutePath
        val newFragment = org.weproz.etab.ui.notes.whiteboard.WhiteboardFragment.newInstance(notesPath, title)
        supportFragmentManager.beginTransaction()
            .replace(R.id.whiteboard_container, newFragment)
            .commit()

        binding.whiteboardContainer.visibility = View.VISIBLE
        binding.splitHandle.visibility = View.VISIBLE

        setupSplitDrag()

        // Set guideline to 50%
        val guideParams = binding.splitGuideline.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        guideParams.guidePercent = 0.5f
        binding.splitGuideline.layoutParams = guideParams

        // PDF Viewer constraints
        val pdfParams = binding.pdfViewer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        pdfParams.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        pdfParams.bottomToTop = R.id.split_guideline
        pdfParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        pdfParams.topToBottom = R.id.top_action_bar
        pdfParams.height = 0
        binding.pdfViewer.layoutParams = pdfParams

        // Whiteboard container constraints
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
        // Save whiteboard when activity is paused
        if (isSplitView) {
            val fragment = supportFragmentManager.findFragmentById(R.id.whiteboard_container) as? org.weproz.etab.ui.notes.whiteboard.WhiteboardFragment
            fragment?.saveWhiteboard()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
    }

    override fun onWordSelected(text: String, selectionRects: List<RectF>, screenRect: RectF, pageIndex: Int) {
        showSelectionMenu(text, selectionRects, screenRect, pageIndex, null)
    }

    override fun onHighlightClicked(highlight: HighlightEntity, rect: RectF) {
        showSelectionMenu(highlight.highlightedText, emptyList(), rect, highlight.chapterIndex, highlight)
    }

    private fun showSelectionMenu(text: String, selectionRects: List<RectF>, rect: RectF, pageIndex: Int, highlight: HighlightEntity?) {
        popupWindow?.dismiss()

        val view = LayoutInflater.from(this).inflate(R.layout.popup_selection_menu, null)
        popupWindow = PopupWindow(
            view,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        val btnDefine = view.findViewById<TextView>(R.id.btn_define)
        val btnHighlight = view.findViewById<TextView>(R.id.btn_highlight)
        val btnCopy = view.findViewById<TextView>(R.id.btn_copy)
        val btnRemove = view.findViewById<TextView>(R.id.btn_remove)
        val sep3 = view.findViewById<View>(R.id.sep_3)

        if (highlight != null) {
            btnHighlight.visibility = View.GONE
            view.findViewById<View>(R.id.sep_2).visibility = View.GONE
            btnRemove.visibility = View.VISIBLE
            sep3.visibility = View.VISIBLE
        } else {
            btnHighlight.visibility = View.VISIBLE
            view.findViewById<View>(R.id.sep_2).visibility = View.VISIBLE
            btnRemove.visibility = View.GONE
            sep3.visibility = View.GONE
        }

        btnDefine.setOnClickListener {
            popupWindow?.dismiss()
            lifecycleScope.launch(Dispatchers.IO) {
                val cleanWord = text.replace(Regex("[^a-zA-Z]"), "")
                val dao = org.weproz.etab.data.local.WordDatabase.getDatabase(this@PdfReaderActivity).wordDao()
                val definition = dao.getDefinition(cleanWord)
                
                withContext(Dispatchers.Main) {
                    if (definition != null) {
                        DefinitionDialogFragment.newInstance(definition)
                            .show(supportFragmentManager, "definition")
                    } else {
                        Toast.makeText(this@PdfReaderActivity, "Definition not found for '$cleanWord'", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnHighlight.setOnClickListener {
            popupWindow?.dismiss()
            saveHighlight(text, selectionRects, pageIndex)
            binding.pdfViewer.clearSelection()
        }

        btnCopy.setOnClickListener {
            popupWindow?.dismiss()
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Copied Text", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            binding.pdfViewer.clearSelection()
        }

        btnRemove.setOnClickListener {
            popupWindow?.dismiss()
            if (highlight != null) {
                removeHighlight(highlight)
            }
        }

        // Calculate position
        val location = IntArray(2)
        binding.pdfViewer.getLocationOnScreen(location)
        
        val x = location[0] + rect.centerX().toInt()
        val y = location[1] + rect.top.toInt() - 150 // Show above

        popupWindow?.showAtLocation(binding.root, Gravity.NO_GRAVITY, x, y)
    }

    private fun saveHighlight(text: String, rects: List<RectF>, pageIndex: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            // Serialize rects to string: "l,t,r,b;l,t,r,b;..."
            val rangeData = rects.joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }
            
            val highlight = HighlightEntity(
                bookPath = pdfPath!!,
                chapterIndex = pageIndex,
                rangeData = rangeData,
                highlightedText = text,
                color = 0xFFFFFF00.toInt(), // Yellow
                createdAt = System.currentTimeMillis()
            )
            
            val dao = AppDatabase.getDatabase(this@PdfReaderActivity).highlightDao()
            dao.insert(highlight)
            
            loadHighlights(pdfPath!!)
        }
    }

    private fun removeHighlight(highlight: HighlightEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(this@PdfReaderActivity).highlightDao()
            dao.delete(highlight)
            loadHighlights(pdfPath!!)
        }
    }
}
