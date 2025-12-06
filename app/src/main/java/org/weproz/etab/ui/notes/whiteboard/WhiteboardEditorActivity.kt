package org.weproz.etab.ui.notes.whiteboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.weproz.etab.R
import org.weproz.etab.data.local.AppDatabase
import org.weproz.etab.data.local.WhiteboardEntity
import org.weproz.etab.databinding.ActivityWhiteboardEditorBinding
import java.io.File
import java.io.FileOutputStream

class WhiteboardEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWhiteboardEditorBinding
    private var whiteboardId: Long = -1
    private var currentTitle = "Untitled Whiteboard"
    private var dataPath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWhiteboardEditorBinding.inflate(layoutInflater)
        binding = ActivityWhiteboardEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        whiteboardId = intent.getLongExtra("whiteboard_id", -1)
        currentTitle = intent.getStringExtra("whiteboard_title") ?: "New Whiteboard"
        dataPath = intent.getStringExtra("whiteboard_data_path") ?: ""
        
        binding.toolbar.title = "" // Hide title as per requirement

        setupTools()
        
        if (whiteboardId != -1L && dataPath.isNotEmpty()) {
            loadWhiteboardData()
        }
    }

    private fun setupTools() {
        binding.toolbar.setNavigationOnClickListener { onBackPressed() }

        binding.btnToolBrush.setOnClickListener { showBrushSettingsDialog() }
        binding.btnToolText.setOnClickListener { showAddTextDialog() }
        binding.btnToolGrid.setOnClickListener { showGridTypeDialog() }
        binding.btnToolUndo.setOnClickListener { binding.whiteboardView.undo() }
        binding.btnToolRedo.setOnClickListener { binding.whiteboardView.redo() }
    }

    private fun showBrushSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_brush_settings, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val groupTool = dialogView.findViewById<android.widget.RadioGroup>(R.id.group_tool_type)
        val radioPen = dialogView.findViewById<android.widget.RadioButton>(R.id.radio_pen)
        val radioEraser = dialogView.findViewById<android.widget.RadioButton>(R.id.radio_eraser)
        val containerColors = dialogView.findViewById<android.widget.LinearLayout>(R.id.container_colors)
        val titleColor = dialogView.findViewById<android.widget.TextView>(R.id.title_color)
        val seekSize = dialogView.findViewById<android.widget.SeekBar>(R.id.seek_size)
        
        // Initial State
        val isEraser = binding.whiteboardView.isEraser
        if (isEraser) {
            radioEraser.isChecked = true
            containerColors.alpha = 0.5f // Greyed out
            containerColors.isEnabled = false // Logic disable
        } else {
            radioPen.isChecked = true
            containerColors.alpha = 1.0f
        }
        
        seekSize.progress = binding.whiteboardView.getStrokeWidth().toInt()
        
        groupTool.setOnCheckedChangeListener { _, checkedId ->
             if (checkedId == R.id.radio_eraser) {
                 binding.whiteboardView.setEraser()
                 containerColors.alpha = 0.5f
                 titleColor.alpha = 0.5f
                 // Use loops or kotlin children to disable clicks if needed
             } else {
                 binding.whiteboardView.setPenColor(binding.whiteboardView.drawColor) // Reset to last color
                 containerColors.alpha = 1.0f
                 titleColor.alpha = 1.0f
             }
        }
        
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
        for (color in colors) {
             val view = android.view.View(this)
             val params = android.widget.LinearLayout.LayoutParams(60, 60)
             params.setMargins(8, 0, 8, 0)
             view.layoutParams = params
             view.setBackgroundColor(color)
             
             // Circular
             val shape = android.graphics.drawable.GradientDrawable()
             shape.shape = android.graphics.drawable.GradientDrawable.OVAL
             shape.setColor(color)
             shape.setStroke(2, Color.DKGRAY)
             view.background = shape
             
             view.setOnClickListener {
                 if (!radioEraser.isChecked) {
                     binding.whiteboardView.setPenColor(color)
                     dialog.dismiss()
                 }
             }
             containerColors.addView(view)
        }
        
        dialog.show()
    }

    private fun showGridTypeDialog() {
        val types = arrayOf("None", "Dot", "Square", "Ruled")
        val typeValues = arrayOf(GridType.NONE, GridType.DOT, GridType.SQUARE, GridType.RULED)
        
        AlertDialog.Builder(this)
            .setTitle("Select Grid Type")
            .setItems(types) { _, which ->
                binding.whiteboardView.gridType = typeValues[which]
            }
            .show()
    }
    
    private fun updateActiveToolUI(activeButton: android.widget.ImageButton) {
        // Unused now with single brush button? Or just highlight brush?
        // Highlight logic can stay if needed, but for now we simplify.
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_whiteboard, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_save_pdf -> {
                saveAsPdf()
                true
            }
            R.id.action_save_image -> {
                saveAsImage()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAddTextDialog() {
        val editText = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("Add Text")
            .setView(editText)
            .setPositiveButton("Add") { _, _ ->
                val text = editText.text.toString()
                if (text.isNotEmpty()) {
                    binding.whiteboardView.addText(text)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /* Rename moved to List Long Press */

    override fun onBackPressed() {
        saveWhiteboard()
        super.onBackPressed()
    }

    private fun saveWhiteboard() {
        lifecycleScope.launch(Dispatchers.IO) {
            val actions = binding.whiteboardView.getPaths()
            val strokes = actions.filterIsInstance<DrawAction.Stroke>()
            val texts = actions.filterIsInstance<DrawAction.Text>()
            
            val json = WhiteboardSerializer.serialize(strokes, texts, binding.whiteboardView.gridType)
            
            val filename = "wb_${System.currentTimeMillis()}.json"
            
            // Determine file to write to
            val actualFile = if (dataPath.isNotEmpty()) {
                File(dataPath)
            } else {
                File(filesDir, filename)
            }
            
            FileOutputStream(actualFile).use { it.write(json.toByteArray()) }
            
            // Update dataPath reference for subsequent saves in this session
            dataPath = actualFile.absolutePath

            // Save Entity
            val dao = AppDatabase.getDatabase(this@WhiteboardEditorActivity).whiteboardDao()
            val entity = WhiteboardEntity(
                id = if (whiteboardId == -1L) 0 else whiteboardId,
                title = currentTitle,
                thumbnailPath = null, // TODO: Generate thumbnail
                dataPath = actualFile.absolutePath,
                updatedAt = System.currentTimeMillis()
            )
            
            if (whiteboardId == -1L) {
               dao.insert(entity)
            } else {
               dao.update(entity)
            }
        }
    }
    
    private fun loadWhiteboardData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = File(dataPath)
                if (!file.exists()) return@launch
                
                val json = file.readText()
                val parsedData = WhiteboardSerializer.deserialize(json)
                
                withContext(Dispatchers.Main) {
                    binding.whiteboardView.gridType = parsedData.gridType
                    binding.whiteboardView.loadPaths(parsedData.actions)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@WhiteboardEditorActivity, "Failed to load whiteboard", Toast.LENGTH_SHORT).show()
                }
            }
        }
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

    private fun saveAsImage() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bitmap = createBitmapFromView()
                val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val imageFile = File(imagesDir, "${currentTitle.replace(" ", "_")}.png")
                
                FileOutputStream(imageFile).use { 
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) 
                }
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@WhiteboardEditorActivity, "Saved Image: ${imageFile.absolutePath}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                 e.printStackTrace()
                 withContext(Dispatchers.Main) {
                    Toast.makeText(this@WhiteboardEditorActivity, "Failed to save Image: ${e.message}", Toast.LENGTH_SHORT).show()
                 }
            }
        }
    }
    
    private suspend fun createBitmapFromView(): Bitmap = withContext(Dispatchers.Main) {
        val view = binding.whiteboardView
        // Capture the full content, currently just captures visible view area which might be zoomed.
        // For a proper full canvas capture, we should ideally reset zoom, layout, capture, then restore.
        // MVP: Capture current view state.
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        bitmap
    }
}
