package org.weproz.etab.ui.custom

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.EditText
import com.google.android.material.card.MaterialCardView
import org.weproz.etab.R
import org.weproz.etab.data.model.whiteboard.GridType
import org.weproz.etab.ui.notes.whiteboard.WhiteboardView

class WhiteboardToolbarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var whiteboardView: WhiteboardView? = null

    private val divider: View
    private val parent: MaterialCardView
    private val btnPen: ImageButton
    private val btnEraser: ImageButton
    private val btnLasso: ImageButton
    private val btnGrid: ImageButton
    private val btnText: ImageButton
    private val btnUndo: ImageButton
    private val btnRedo: ImageButton
    private val btnClear: ImageButton

    init {
        LayoutInflater.from(context).inflate(R.layout.view_whiteboard_toolbar, this, true)

        divider = findViewById(R.id.divider)
        btnPen = findViewById(R.id.btn_tool_pen)
        btnEraser = findViewById(R.id.btn_tool_eraser)
        btnLasso = findViewById(R.id.btn_tool_lasso)
        btnGrid = findViewById(R.id.btn_tool_grid)
        btnText = findViewById(R.id.btn_tool_text)
        btnUndo = findViewById(R.id.btn_tool_undo)
        btnRedo = findViewById(R.id.btn_tool_redo)
        btnClear = findViewById(R.id.btn_tool_clear)
        parent = findViewById(R.id.toolbar_parent)
        
        setupListeners()
        updateActiveToolUI(btnPen) // Default
    }
    
    fun attachTo(view: WhiteboardView) {
        this.whiteboardView = view
    }

    override fun setBackgroundColor(color: Int) {
        parent.setCardBackgroundColor(color)
    }
    
    private fun setupListeners() {
        btnPen.setOnClickListener {
            whiteboardView?.setTool(WhiteboardView.ToolType.PEN)
            updateActiveToolUI(btnPen)
            showPenSettingsPopup(it)
        }
        
        btnEraser.setOnClickListener {
            whiteboardView?.setTool(WhiteboardView.ToolType.ERASER)
            updateActiveToolUI(btnEraser)
            showEraserSettingsPopup(it)
        }
        

        btnGrid.setOnClickListener { showGridSettingsPopup(it) }
        btnText.setOnClickListener { showAddTextDialog() }
        btnLasso.setOnClickListener {
            whiteboardView?.setTool(WhiteboardView.ToolType.SELECTOR)
            updateActiveToolUI(btnLasso)
        }
        
        btnUndo.setOnClickListener { whiteboardView?.undo() }
        btnRedo.setOnClickListener { whiteboardView?.redo() }
        btnClear.setOnClickListener { showClearConfirmationDialog() }
    }
    
    private fun updateActiveToolUI(activeButton: ImageButton) {
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typedValue, true)
        val backgroundResource = typedValue.resourceId
        
        btnPen.setBackgroundResource(backgroundResource)
        btnEraser.setBackgroundResource(backgroundResource)
        btnLasso.setBackgroundResource(backgroundResource)
        
        activeButton.setBackgroundResource(R.drawable.bg_toolbar_tool)
    }

    private fun showPenSettingsPopup(anchor: View) {
        val view = LayoutInflater.from(context).inflate(R.layout.popup_pen_settings, null)
        val popup = PopupWindow(view, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = 10f
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        
        val containerColors = view.findViewById<LinearLayout>(R.id.container_colors)
        val seekSize = view.findViewById<SeekBar>(R.id.seek_size)
        val groupType = view.findViewById<android.widget.RadioGroup>(R.id.group_pen_type)
        
        val wb = whiteboardView ?: return
        
        // Pen Type
        if (wb.isHighlighter) {
            groupType.check(R.id.radio_highlighter)
        } else {
            groupType.check(R.id.radio_pen)
        }
        
        groupType.setOnCheckedChangeListener { _, checkedId ->
            wb.isHighlighter = (checkedId == R.id.radio_highlighter)
        }
        
        seekSize.progress = wb.getStrokeWidth().toInt()
        seekSize.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
             override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                 val size = progress.coerceAtLeast(1).toFloat()
                 wb.setStrokeWidthGeneric(size)
             }
             override fun onStartTrackingTouch(seekBar: SeekBar?) {}
             override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Colors
        val colors = intArrayOf(Color.BLACK, Color.RED, Color.BLUE, Color.GREEN, Color.MAGENTA, Color.CYAN, Color.YELLOW)
        val currentColor = wb.drawColor
        
        for (color in colors) {
             val colorView = View(context)
             val params = LinearLayout.LayoutParams(60, 60)
             params.setMargins(8, 0, 8, 0)
             colorView.layoutParams = params
             
             val shape = android.graphics.drawable.GradientDrawable()
             shape.shape = android.graphics.drawable.GradientDrawable.OVAL
             shape.setColor(color)
             
             if (color == currentColor) {
                 shape.setStroke(6, Color.DKGRAY)
             } else {
                 shape.setStroke(2, Color.LTGRAY)
             }
             
             colorView.background = shape
             
             colorView.setOnClickListener {
                 wb.drawColor = color
                 popup.dismiss()
             }
             containerColors.addView(colorView)
        }
        
        popup.showAsDropDown(anchor, 0, 10)
    }
    
    private fun showEraserSettingsPopup(anchor: View) {
        val view = LayoutInflater.from(context).inflate(R.layout.popup_eraser_settings, null)
        val popup = PopupWindow(view, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = 10f
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        
        val seekSize = view.findViewById<SeekBar>(R.id.seek_size)
        val wb = whiteboardView ?: return
        seekSize.progress = wb.getStrokeWidth().toInt()
        
        seekSize.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
             override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                 val size = progress.coerceAtLeast(1).toFloat()
                 wb.setStrokeWidthGeneric(size)
             }
             override fun onStartTrackingTouch(seekBar: SeekBar?) {}
             override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        popup.showAsDropDown(anchor, 0, 10)
    }

    private fun showGridSettingsPopup(anchor: View) {
        val view = LayoutInflater.from(context).inflate(R.layout.popup_grid_settings, null)
        val popup = PopupWindow(view, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = 10f
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        
        val group = view.findViewById<RadioGroup>(R.id.group_grid_type)
        val wb = whiteboardView ?: return
        val currentType = wb.gridType
        
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
            wb.gridType = type
            popup.dismiss()
        }
        
        popup.showAsDropDown(anchor, 0, 10)
    }

    private fun showAddTextDialog() {
        val editText = EditText(context)
        editText.hint = "Enter text"
        editText.setHintTextColor(Color.GRAY)
        editText.setPadding(32, 16, 32, 16)
        CustomDialog(context)
            .setTitle("Add Text")
            .setView(editText)
            .setPositiveButton("Add") { dialog ->
                val text = editText.text.toString()
                if (text.isNotEmpty()) {
                    whiteboardView?.addText(text)
                    dialog.dismiss()
                }
            }
            .setNegativeButton("Cancel")
            .show()
    }

    private fun showClearConfirmationDialog() {
        CustomDialog(context)
            .setTitle("Clear Annotations")
            .setMessage("Are you sure you want to clear all annotations?")
            .setPositiveButton("Clear") { dialog ->
                whiteboardView?.clear()
                whiteboardView?.onActionCompleted?.invoke()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel")
            .show()
    }

    fun setDividerVisibility(visible: Int) {
        divider.visibility = visible
    }

    fun setStrokeColor(color: Int) {
        parent.setStrokeColor(color)
    }

    fun setStrokeWidth(f: Int) {
        parent.strokeWidth = f
    }
}
