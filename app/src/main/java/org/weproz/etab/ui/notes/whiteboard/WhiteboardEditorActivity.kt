package org.weproz.etab.ui.notes.whiteboard

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.weproz.etab.R
import org.weproz.etab.data.model.whiteboard.GridType
import org.weproz.etab.data.model.whiteboard.ParsedPage
import org.weproz.etab.data.serializer.WhiteboardSerializer
import org.weproz.etab.databinding.ActivityWhiteboardEditorBinding
import org.weproz.etab.ui.custom.CustomPopupMenu

@AndroidEntryPoint
class WhiteboardEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWhiteboardEditorBinding
    private val viewModel: WhiteboardEditorViewModel by viewModels()
    private var whiteboardId: Long = -1
    private var currentTitle = "Untitled Whiteboard"
    private var dataPath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWhiteboardEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        whiteboardId = intent.getLongExtra("whiteboard_id", -1)
        currentTitle = intent.getStringExtra("whiteboard_title") ?: ""  // Empty means new whiteboard, will be set on save
        dataPath = intent.getStringExtra("whiteboard_data_path") ?: ""
        
        binding.toolbar.title = "" // Hide title as per requirement
        
        // Initialize with at least one empty page if needed
        if (viewModel.pages.isEmpty()) {
            viewModel.pages.add(ParsedPage(emptyList(), GridType.NONE))
        }

        binding.whiteboardToolbar.attachTo(binding.whiteboardView)
        setupTools()
        setupPageNavigation()
        
        binding.whiteboardView.onActionCompleted = {
            saveWhiteboard()
        }
        
        if (!viewModel.isDataLoaded && whiteboardId != -1L && dataPath.isNotEmpty()) {
            loadWhiteboardData()
        } else {
            // If already loaded (rotation) or new, just load current page
            loadCurrentPage()
        }

    }

    private fun setupTools() {
        // Back button
        binding.btnBack.setOnClickListener {
            saveWhiteboard()
            finish()
        }

        // Save button
        binding.btnSave.setOnClickListener { view ->
            CustomPopupMenu(this, view)
                .addItem("Save", R.drawable.ic_save) {
                    saveWhiteboard()
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                }
                .addItem("Save as PDF", R.drawable.ic_pdf) {
                    saveAsPdf()
                }
                .show()
        }
    }

    
    private fun setupPageNavigation() {
        binding.btnPrevPage.setOnClickListener {
            if (viewModel.currentPageIndex > 0) {
                saveCurrentPage()
                viewModel.currentPageIndex--
                loadCurrentPage()
            }
        }
        
        binding.btnNextPage.setOnClickListener {
            if (viewModel.currentPageIndex < viewModel.pages.size - 1) {
                saveCurrentPage()
                viewModel.currentPageIndex++
                loadCurrentPage()
            }
        }
        
        binding.btnAddPage.setOnClickListener {
            android.util.Log.d("WhiteboardActivity", "ADD PAGE clicked: currently on page ${viewModel.currentPageIndex} with ${binding.whiteboardView.getPaths().size} actions")
            saveCurrentPage()
            viewModel.pages.add(ParsedPage(emptyList(), GridType.NONE))
            viewModel.currentPageIndex = viewModel.pages.size - 1
            android.util.Log.d("WhiteboardActivity", "Added new page, now have ${viewModel.pages.size} pages, moved to page ${viewModel.currentPageIndex}")
            loadCurrentPage()
            Toast.makeText(this, "New page added", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun saveCurrentPage() {
        if (viewModel.currentPageIndex < viewModel.pages.size) {
            val actions = binding.whiteboardView.getPaths().toList() // Create a copy!
            val gridType = binding.whiteboardView.gridType
            val oldPage = viewModel.pages[viewModel.currentPageIndex]
            viewModel.pages[viewModel.currentPageIndex] = ParsedPage(actions, gridType)
            android.util.Log.d("WhiteboardActivity", "Saved page ${viewModel.currentPageIndex}: ${oldPage.actions.size} -> ${actions.size} actions, total pages: ${viewModel.pages.size}")
        } else {
            android.util.Log.e("WhiteboardActivity", "ERROR: Cannot save page ${viewModel.currentPageIndex}, pages.size=${viewModel.pages.size}")
        }
    }
    
    private fun loadCurrentPage() {
        if (viewModel.currentPageIndex < viewModel.pages.size) {
            val page = viewModel.pages[viewModel.currentPageIndex]
            binding.whiteboardView.gridType = page.gridType
            binding.whiteboardView.loadPaths(page.actions)
            updatePageIndicator()
            android.util.Log.d("WhiteboardActivity", "Loaded page ${viewModel.currentPageIndex} with ${page.actions.size} actions")
        }
    }
    
    private fun updatePageIndicator() {
        val pageNum = viewModel.currentPageIndex + 1
        val totalPages = viewModel.pages.size
        binding.textPageIndicator.text = "Page $pageNum of $totalPages"
        
        binding.btnPrevPage.isEnabled = viewModel.currentPageIndex > 0
        binding.btnNextPage.isEnabled = viewModel.currentPageIndex < viewModel.pages.size - 1
        
        binding.btnPrevPage.alpha = if (viewModel.currentPageIndex > 0) 1.0f else 0.3f
        binding.btnNextPage.alpha = if (viewModel.currentPageIndex < viewModel.pages.size - 1) 1.0f else 0.3f
    }

    override fun onBackPressed() {
        saveWhiteboard()
        super.onBackPressed()
    }

    private fun saveWhiteboard() {
        saveCurrentPage()
        
        val json = WhiteboardSerializer.serialize(viewModel.pages)
        
        viewModel.saveWhiteboard(
            whiteboardId = whiteboardId,
            currentTitle = currentTitle,
            dataPath = dataPath,
            pagesJson = json,
            filesDir = filesDir,
            onComplete = { newId, newTitle, newDataPath ->
                whiteboardId = newId
                currentTitle = newTitle
                dataPath = newDataPath
            }
        )
    }
    
    private fun loadWhiteboardData() {
        viewModel.loadWhiteboardData(
            dataPath = dataPath,
            onLoaded = { json ->
                val parsedData = WhiteboardSerializer.deserialize(json)
                viewModel.pages.clear()
                viewModel.pages.addAll(parsedData.pages)
                
                if (viewModel.pages.isEmpty()) {
                    viewModel.pages.add(ParsedPage(emptyList(), GridType.NONE))
                }
                
                viewModel.currentPageIndex = 0
                viewModel.isDataLoaded = true
                loadCurrentPage()
            },
            onError = {
                Toast.makeText(this@WhiteboardEditorActivity, "Failed to load whiteboard", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun saveAsPdf() {
        saveCurrentPage()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val width = binding.whiteboardView.width
                val height = binding.whiteboardView.height
                val pages = viewModel.pages.toList()
                
                val exporter = WhiteboardPdfExporter(this@WhiteboardEditorActivity)
                val pdfFile = exporter.exportToPdf(pages, width, height, currentTitle)
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@WhiteboardEditorActivity, "Saved to ${pdfFile.absolutePath}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                   Toast.makeText(this@WhiteboardEditorActivity, "Failed to save PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
