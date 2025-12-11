package org.weproz.etab.ui.reader

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.weproz.etab.R
import org.weproz.etab.data.local.AppDatabase
import org.weproz.etab.databinding.ActivityPdfReaderBinding
import java.io.File

/**
 * Activity for viewing PDF files with a Google Drive-like experience.
 * Features:
 * - Pinch-to-zoom and pan gestures
 * - Page-by-page view with gaps between pages
 * - Zoom controls
 * - Page indicator
 * - Split view with whiteboard for notes
 */
class PdfReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfReaderBinding
    private var pdfPath: String? = null
    private var isSplitView = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        pdfPath = intent.getStringExtra("book_path")

        if (pdfPath != null) {
            loadPdf(pdfPath!!)
            updateLastOpened(pdfPath!!)
        } else {
            Toast.makeText(this, "Error loading PDF", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupControls()
        setupPageListener()
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

        // Notes toggle for split view
        binding.btnNotesToggle.setOnClickListener {
            toggleSplitView()
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
            params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            params.height = 0
            binding.pdfViewer.layoutParams = params

            isSplitView = false
        } else {
            // Split screen mode - create a NEW whiteboard each time
            if (pdfPath != null) {
                // Use timestamp to create unique file path for each session
                val timestamp = System.currentTimeMillis()
                val notesPath = File(getExternalFilesDir(null), "wb_pdf_$timestamp.json").absolutePath
                val newFragment = org.weproz.etab.ui.notes.whiteboard.WhiteboardFragment.newInstance(notesPath)
                supportFragmentManager.beginTransaction()
                    .replace(R.id.whiteboard_container, newFragment)
                    .commit()
            }

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
            pdfParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
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
}
