package org.weproz.etab.ui.custom

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import org.weproz.etab.R

class CustomPopupMenu(private val context: Context, private val anchor: View) {

    private val popupWindow: PopupWindow
    private val container: LinearLayout

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.popup_custom_menu, null)
        container = view.findViewById(R.id.menu_container)

        popupWindow = PopupWindow(
            view,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.elevation = 10f
    }

    fun addItem(title: String, iconRes: Int? = null, onClick: () -> Unit): CustomPopupMenu {
        val itemView = LayoutInflater.from(context).inflate(R.layout.item_custom_menu, container, false)
        val textView = itemView.findViewById<TextView>(R.id.menu_text)
        val iconView = itemView.findViewById<ImageView>(R.id.menu_icon)

        textView.text = title
        if (iconRes != null) {
            iconView.setImageResource(iconRes)
            iconView.visibility = View.VISIBLE
        } else {
            iconView.visibility = View.GONE
        }

        itemView.setOnClickListener {
            onClick()
            popupWindow.dismiss()
        }

        container.addView(itemView)
        return this
    }

    fun show() {
        popupWindow.showAsDropDown(anchor, 0, 0)
    }
}