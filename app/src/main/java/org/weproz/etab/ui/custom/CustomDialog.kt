package org.weproz.etab.ui.custom

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import org.weproz.etab.R

class CustomDialog(context: Context) {

    private val dialog: Dialog = Dialog(context)
    private val titleView: TextView
    private val contentContainer: FrameLayout
    private val positiveButton: Button
    private val negativeButton: Button

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_custom, null)
        dialog.setContentView(view)

        titleView = view.findViewById(R.id.dialog_title)
        contentContainer = view.findViewById(R.id.dialog_content_container)
        positiveButton = view.findViewById(R.id.btn_positive)
        negativeButton = view.findViewById(R.id.btn_negative)

        negativeButton.setOnClickListener { dialog.dismiss() }
    }

    fun setTitle(title: String): CustomDialog {
        titleView.text = title
        titleView.visibility = View.VISIBLE
        return this
    }

    fun setView(view: View): CustomDialog {
        contentContainer.removeAllViews()
        contentContainer.addView(view)
        return this
    }
    
    fun setMessage(message: String): CustomDialog {
        val textView = TextView(dialog.context)
        textView.text = message
        textView.textSize = 16f
        textView.setTextColor(Color.DKGRAY)
        contentContainer.removeAllViews()
        contentContainer.addView(textView)
        return this
    }

    fun setPositiveButton(text: String, onClick: (Dialog) -> Unit): CustomDialog {
        positiveButton.text = text
        positiveButton.setOnClickListener { onClick(dialog) }
        positiveButton.visibility = View.VISIBLE
        return this
    }

    fun setNegativeButton(text: String, onClick: ((Dialog) -> Unit)? = null): CustomDialog {
        negativeButton.text = text
        negativeButton.setOnClickListener { 
            onClick?.invoke(dialog)
            dialog.dismiss() 
        }
        negativeButton.visibility = View.VISIBLE
        return this
    }

    fun show() {
        dialog.show()
    }
}
