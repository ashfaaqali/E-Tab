package org.weproz.etab.ui.notes.whiteboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import org.weproz.etab.data.serializer.WhiteboardSerializer
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.weproz.etab.R
import org.weproz.etab.databinding.ActivityWhiteboardEditorBinding
import java.io.File
import java.io.FileOutputStream
import androidx.core.view.size

import android.widget.PopupWindow
import android.view.ViewGroup
import org.weproz.etab.data.model.whiteboard.GridType
import org.weproz.etab.data.model.whiteboard.ParsedPage
import org.weproz.etab.ui.custom.CustomDialog

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

        binding.btnToolPen.setOnClickListener { 
            binding.whiteboardView.setTool(WhiteboardView.ToolType.PEN)
            updateActiveToolUI(binding.btnToolPen)
            showPenSettingsPopup(it) 
        }
        
        binding.btnToolEraser.setOnClickListener { 
            binding.whiteboardView.setTool(WhiteboardView.ToolType.ERASER)
            updateActiveToolUI(binding.btnToolEraser)
            showEraserSettingsPopup(it) 
        }
        
        binding.btnToolText.setOnClickListener { showAddTextDialog() }
        
        binding.btnToolGrid.setOnClickListener { 
            showGridSettingsPopup(it) 
        }
        
        binding.btnToolUndo.setOnClickListener { binding.whiteboardView.undo() }
        binding.btnToolRedo.setOnClickListener { binding.whiteboardView.redo() }
        
        binding.btnToolClear.setOnClickListener { showClearConfirmationDialog() }
        
        // Initial UI state
        updateActiveToolUI(binding.btnToolPen)
    }

    private fun showClearConfirmationDialog() {
        CustomDialog(this)
            .setTitle("Clear Whiteboard")
            .setMessage("Are you sure you want to clear the entire whiteboard? This cannot be undone.")
            .setPositiveButton("Clear") { dialog ->
                binding.whiteboardView.clear()
                binding.whiteboardView.onActionCompleted?.invoke() // Trigger save
                dialog.dismiss()
            }
            .setNegativeButton("Cancel")
            .show()
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

    private fun showPenSettingsPopup(anchor: android.view.View) {
        val view = LayoutInflater.from(this).inflate(R.layout.popup_pen_settings, null)
        val popup = PopupWindow(view, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = 10f
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        
        val containerColors = view.findViewById<android.widget.LinearLayout>(R.id.container_colors)
        val seekSize = view.findViewById<android.widget.SeekBar>(R.id.seek_size)
        val groupType = view.findViewById<android.widget.RadioGroup>(R.id.group_pen_type)
        
        // Pen Type
        if (binding.whiteboardView.isHighlighter) {
            groupType.check(R.id.radio_highlighter)
        } else {
            groupType.check(R.id.radio_pen)
        }
        
        groupType.setOnCheckedChangeListener { _, checkedId ->
            binding.whiteboardView.isHighlighter = (checkedId == R.id.radio_highlighter)
        }
        
        seekSize.progress = binding.whiteboardView.getStrokeWidth().toInt()
        seekSize.setOnSeekBarChangeListener(object: android.widget.SeekBar.OnSeekBarChangeListener {
             override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                 val size = progress.coerceAtLeast(1).toFloat()
                 binding.whiteboardView.setStrokeWidthGeneric(size)
             }
             override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
             override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        
        // Colors
        val colors = intArrayOf(Color.BLACK, Color.RED, Color.BLUE, Color.GREEN, Color.MAGENTA, Color.CYAN, Color.YELLOW)
        val currentColor = binding.whiteboardView.drawColor
        
        for (color in colors) {
             val colorView = android.view.View(this)
             val params = android.widget.LinearLayout.LayoutParams(60, 60)
             params.setMargins(8, 0, 8, 0)
             colorView.layoutParams = params
             
             val shape = android.graphics.drawable.GradientDrawable()
             shape.shape = android.graphics.drawable.GradientDrawable.OVAL
             shape.setColor(color)
             
             if (color == currentColor) {
                 shape.setStroke(6, Color.DKGRAY) // Selected indicator
             } else {
                 shape.setStroke(2, Color.LTGRAY)
             }
             
             colorView.background = shape
             
             colorView.setOnClickListener {
                 binding.whiteboardView.drawColor = color
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
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        
        val seekSize = view.findViewById<android.widget.SeekBar>(R.id.seek_size)
        seekSize.progress = binding.whiteboardView.getStrokeWidth().toInt()
        
        seekSize.setOnSeekBarChangeListener(object: android.widget.SeekBar.OnSeekBarChangeListener {
             override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                 val size = progress.coerceAtLeast(1).toFloat()
                 binding.whiteboardView.setStrokeWidthGeneric(size)
             }
             override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
             override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        
        popup.showAsDropDown(anchor, 0, 10)
    }

    private fun showGridSettingsPopup(anchor: android.view.View) {
        val view = LayoutInflater.from(this).inflate(R.layout.popup_grid_settings, null)
        val popup = PopupWindow(view, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = 10f
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        
        val group = view.findViewById<android.widget.RadioGroup>(R.id.group_grid_type)
        val currentType = binding.whiteboardView.gridType
        
        when (currentType) {
            GridType.NONE -> group.check(R.id.radio_none)
            GridType.DOT -> group.check(R.id.radio_dot)
            GridType.SQUARE -> group.check(R.id.radio_square)
            GridType.RULED -> group.check(R.id.radio_ruled)
        }
        
        group.setOnCheckedChangeListener { _, checkedId ->
            val type = when (checkedId) {
                R.id.radio_none -> GridType.NONE
                R.id.radio_dot -> GridType.DOT
                R.id.radio_square -> GridType.SQUARE
                R.id.radio_ruled -> GridType.RULED
                else -> GridType.NONE
            }
            binding.whiteboardView.gridType = type
            popup.dismiss()
        }
        
        popup.showAsDropDown(anchor, 0, 10)
    }
    
    private fun updateActiveToolUI(activeButton: android.widget.ImageButton) {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typedValue, true)
        val backgroundResource = typedValue.resourceId
        
        binding.btnToolPen.setBackgroundResource(backgroundResource)
        binding.btnToolEraser.setBackgroundResource(backgroundResource)
        
        activeButton.setBackgroundResource(R.drawable.bg_toolbar_tool)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_whiteboard, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        for (i in 0 until (menu?.size ?: 0)) {
            val item = menu?.getItem(i)
            val spanString = android.text.SpannableString(item?.title.toString())
            spanString.setSpan(
                android.text.style.ForegroundColorSpan(android.graphics.Color.BLACK),
                0,
                spanString.length,
                0
            )
            item?.title = spanString
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_save -> {
                saveWhiteboard()
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_save_pdf -> {
                saveAsPdf()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAddTextDialog() {
        val editText = EditText(this)
        org.weproz.etab.ui.custom.CustomDialog(this)
            .setTitle("Add Text")
            .setView(editText)
            .setPositiveButton("Add") { dialog ->
                val text = editText.text.toString()
                if (text.isNotEmpty()) {
                    binding.whiteboardView.addText(text)
                    dialog.dismiss()
                }
            }
            .setNegativeButton("Cancel")
            .show()
    }

    /* Rename moved to List Long Press */

    override fun onBackPressed() {
        saveWhiteboard()
        super.onBackPressed()
    }

    private fun saveWhiteboard() {
        saveCurrentPage() // Ensure current page is saved
        
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
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Create Bitmap from View
                val bitmap = createBitmapFromView()
                
                // 2. Create PDF
                val pdfDocument = PdfDocument()
                val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
                val page = pdfDocument.startPage(pageInfo)
                
                val canvas = page.canvas
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                pdfDocument.finishPage(page)
                
                // 3. Save to Downloads
                val pdfDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val pdfFile = File(pdfDir, "${currentTitle.replace(" ", "_")}.pdf")
                
                FileOutputStream(pdfFile).use { 
                    pdfDocument.writeTo(it) 
                }
                pdfDocument.close()
                
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

    private suspend fun createBitmapFromView(): Bitmap = withContext(Dispatchers.Main) {
        val view = binding.whiteboardView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        bitmap
    }
}
