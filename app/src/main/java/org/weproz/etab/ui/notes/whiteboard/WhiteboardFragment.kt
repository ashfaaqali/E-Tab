package org.weproz.etab.ui.notes.whiteboard

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.weproz.etab.R
import org.weproz.etab.databinding.FragmentWhiteboardEditorBinding
import java.io.File
import java.io.FileOutputStream

class WhiteboardFragment : Fragment() {

    private var _binding: FragmentWhiteboardEditorBinding? = null
    private val binding get() = _binding!!

    private var dataPath: String? = null
    
    companion object {
        private const val ARG_DATA_PATH = "arg_data_path"

        fun newInstance(dataPath: String): WhiteboardFragment {
            val fragment = WhiteboardFragment()
            val args = Bundle()
            args.putString(ARG_DATA_PATH, dataPath)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            dataPath = it.getString(ARG_DATA_PATH)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWhiteboardEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTools()
        if (dataPath != null) {
            loadWhiteboardData()
        }
    }

    override fun onPause() {
        super.onPause()
        saveWhiteboard()
    }

    private fun setupTools() {
        binding.btnToolBrush.setOnClickListener { showBrushSettingsDialog() }
        binding.btnToolText.setOnClickListener { showAddTextDialog() }
        binding.btnToolGrid.setOnClickListener { showGridTypeDialog() }
        binding.btnToolUndo.setOnClickListener { binding.whiteboardView.undo() }
        binding.btnToolRedo.setOnClickListener { binding.whiteboardView.redo() }
    }

    private fun showBrushSettingsDialog() {
        val context = requireContext()
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_brush_settings, null)
        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .create()

        val radioPen = dialogView.findViewById<android.widget.RadioButton>(R.id.radio_pen)
        val radioEraser = dialogView.findViewById<android.widget.RadioButton>(R.id.radio_eraser)
        val containerColors = dialogView.findViewById<android.widget.LinearLayout>(R.id.container_colors)
        val seekSize = dialogView.findViewById<android.widget.SeekBar>(R.id.seek_size)
        // Note: group_tool_type needs listener setup like in Activity if we want eraser logic to properly toggle UI state
        // Keeping it simple for now, copying key logic.
        val groupTool = dialogView.findViewById<android.widget.RadioGroup>(R.id.group_tool_type)
        
        // Initial State
        val isEraser = binding.whiteboardView.isEraser
        if (isEraser) {
            radioEraser.isChecked = true
            containerColors.alpha = 0.5f 
            containerColors.isEnabled = false 
        } else {
            radioPen.isChecked = true
            containerColors.alpha = 1.0f
        }
        
        seekSize.progress = binding.whiteboardView.getStrokeWidth().toInt()
        
         groupTool.setOnCheckedChangeListener { _, checkedId ->
             if (checkedId == R.id.radio_eraser) {
                 binding.whiteboardView.setEraser()
                 containerColors.alpha = 0.5f
             } else {
                 binding.whiteboardView.setPenColor(binding.whiteboardView.drawColor)
                 containerColors.alpha = 1.0f
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
        
        val colors = intArrayOf(Color.BLACK, Color.RED, Color.BLUE, Color.GREEN, Color.MAGENTA, Color.CYAN, Color.YELLOW)
        for (color in colors) {
             val view = android.view.View(context)
             val params = android.widget.LinearLayout.LayoutParams(60, 60)
             params.setMargins(8, 0, 8, 0)
             view.layoutParams = params
             view.setBackgroundColor(color)
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
        
        AlertDialog.Builder(requireContext())
            .setTitle("Select Grid Type")
            .setItems(types) { _, which ->
                binding.whiteboardView.gridType = typeValues[which]
            }
            .show()
    }

    private fun showAddTextDialog() {
        val editText = EditText(requireContext())
        AlertDialog.Builder(requireContext())
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

    fun saveWhiteboard() {
        if (dataPath == null) return
        
        lifecycleScope.launch(Dispatchers.IO) {
            val actions = binding.whiteboardView.getPaths()
            val strokes = actions.filterIsInstance<DrawAction.Stroke>()
            val texts = actions.filterIsInstance<DrawAction.Text>()
            
            val json = WhiteboardSerializer.serialize(strokes, texts, binding.whiteboardView.gridType)
            
            val file = File(dataPath!!)
            // Create parent dirs if needed
            file.parentFile?.mkdirs()
            
            FileOutputStream(file).use { it.write(json.toByteArray()) }
        }
    }
    
    private fun loadWhiteboardData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (dataPath == null) return@launch
                val file = File(dataPath!!)
                if (!file.exists()) return@launch
                
                val json = file.readText()
                val parsedData = WhiteboardSerializer.deserialize(json)
                
                withContext(Dispatchers.Main) {
                    binding.whiteboardView.gridType = parsedData.gridType
                    binding.whiteboardView.loadPaths(parsedData.actions)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
