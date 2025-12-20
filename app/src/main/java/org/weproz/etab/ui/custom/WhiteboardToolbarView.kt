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
import android.widget.SeekBar
import org.weproz.etab.R
import org.weproz.etab.ui.notes.whiteboard.WhiteboardView

class WhiteboardToolbarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var whiteboardView: WhiteboardView? = null
    
    private val btnPen: ImageButton
    private val btnEraser: ImageButton
    private val btnLasso: ImageButton
    private val btnUndo: ImageButton
    private val btnRedo: ImageButton
    private val btnClear: ImageButton

    init {
        LayoutInflater.from(context).inflate(R.layout.view_whiteboard_toolbar, this, true)
        
        btnPen = findViewById(R.id.btn_tool_pen)
        btnEraser = findViewById(R.id.btn_tool_eraser)
        btnLasso = findViewById(R.id.btn_tool_lasso)
        btnUndo = findViewById(R.id.btn_tool_undo)
        btnRedo = findViewById(R.id.btn_tool_redo)
        btnClear = findViewById(R.id.btn_tool_clear)
        
        setupListeners()
        updateActiveToolUI(btnPen) // Default
    }
    
    fun attachTo(view: WhiteboardView) {
        this.whiteboardView = view
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

    private fun showClearConfirmationDialog() {
        CustomDialog(context)
            .setTitle("Clear Annotations")
            .setMessage("Are you sure you want to clear all annotations?")
            .setPositiveButton("Clear") { dialog ->
                whiteboardView?.clear()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel")
            .show()
    }
}
