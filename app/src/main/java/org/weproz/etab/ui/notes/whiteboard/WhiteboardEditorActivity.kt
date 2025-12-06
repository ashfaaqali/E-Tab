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
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        whiteboardId = intent.getLongExtra("whiteboard_id", -1)
        currentTitle = intent.getStringExtra("whiteboard_title") ?: "New Whiteboard"
        dataPath = intent.getStringExtra("whiteboard_data_path") ?: ""
        
        supportActionBar?.title = currentTitle

        setupTools()
        
        if (whiteboardId != -1L && dataPath.isNotEmpty()) {
            loadWhiteboardData()
        }
    }

    private fun setupTools() {
        binding.toolbar.setNavigationOnClickListener { onBackPressed() }

        binding.btnColorBlack.setOnClickListener { binding.whiteboardView.setPenColor(Color.BLACK) }
        binding.btnColorRed.setOnClickListener { binding.whiteboardView.setPenColor(Color.RED) }
        binding.btnColorBlue.setOnClickListener { binding.whiteboardView.setPenColor(Color.BLUE) }
        binding.btnEraser.setOnClickListener { binding.whiteboardView.setEraser() }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_whiteboard, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_undo -> {
                binding.whiteboardView.undo()
                true
            }
            R.id.action_redo -> {
                binding.whiteboardView.redo()
                true
            }
            R.id.action_text -> {
                showAddTextDialog()
                true
            }
            R.id.action_save_pdf -> {
                saveAsPdf()
                true
            }
            R.id.action_rename -> {
                showRenameDialog()
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

    private fun showRenameDialog() {
        val editText = EditText(this)
        editText.setText(currentTitle)
        AlertDialog.Builder(this)
            .setTitle("Rename Whiteboard")
            .setView(editText)
            .setPositiveButton("Rename") { _, _ ->
                currentTitle = editText.text.toString()
                supportActionBar?.title = currentTitle
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onBackPressed() {
        saveWhiteboard()
        super.onBackPressed()
    }

    private fun saveWhiteboard() {
        lifecycleScope.launch(Dispatchers.IO) {
            val gson = Gson()
            val actions = binding.whiteboardView.getPaths()
            
            val serializableStrokes = actions.filterIsInstance<DrawAction.Stroke>().map { action ->
                SerializableStroke(action.points, action.color, action.strokeWidth) 
            }
            val serializableTexts = actions.filterIsInstance<DrawAction.Text>().map { 
                SerializableText(it.text, it.x, it.y, it.color, it.textSize)
            }
            val data = WhiteboardData(serializableStrokes, serializableTexts)
            
            val json = gson.toJson(data)
            
            val filename = "wb_${System.currentTimeMillis()}.json"
            val file = File(filesDir, filename) // Save internal private storage
            // if editing, overwrite existing file if path known? Logic simplified for new files.
            
            // Actually, correct logic:
            val actualFile = if (dataPath.isNotEmpty()) File(dataPath) else File(filesDir, "wb_${System.currentTimeMillis()}.json")
            FileOutputStream(actualFile).use { it.write(json.toByteArray()) }

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
    
    // Placeholder loading
    private fun loadWhiteboardData() {
        // TODO: Implement loading from JSON
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
        // Capture the full content, currently just captures visible view area which might be zoomed.
        // For a proper full canvas capture, we should ideally reset zoom, layout, capture, then restore.
        // MVP: Capture current view state.
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        bitmap
    }
}
