package org.weproz.etab.ui.custom

import android.content.Context
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.EditText
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.card.MaterialCardView
import org.weproz.etab.R

class ETabSearchBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialCardView(context, attrs, defStyleAttr) {

    private val editText: EditText

    init {
        LayoutInflater.from(context).inflate(R.layout.view_custom_search_bar, this, true)
        editText = findViewById(R.id.et_search)
        radius = 1000f
        elevation = 0f
//        strokeWidth = 0
//        strokeColor = context.getColor(R.color.bottom_bar_content)
        context.theme.obtainStyledAttributes(
            attrs,
            R.styleable.CustomSearchBar,
            0, 0
        ).apply {
            try {
                val hint = getString(R.styleable.CustomSearchBar_searchHint)
                if (hint != null) {
                    editText.hint = hint
                }
                val isReadOnly = getBoolean(R.styleable.CustomSearchBar_isReadOnly, false)
                setReadOnly(isReadOnly)
            } finally {
                recycle()
            }
        }
    }

    fun setReadOnly(readOnly: Boolean) {
        if (readOnly) {
            editText.isFocusable = false
            editText.isClickable = true
            editText.isLongClickable = false
            editText.inputType = 0 // TYPE_NULL
            editText.setOnClickListener { performClick() }
        } else {
            editText.isFocusable = true
            editText.isFocusableInTouchMode = true
            editText.isClickable = true
            editText.inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
    }

    fun addTextChangedListener(watcher: TextWatcher) {
        editText.addTextChangedListener(watcher)
    }

    fun getText(): String {
        return editText.text.toString()
    }
    
    fun requestInputFocus() {
        editText.requestFocus()
    }

    override fun setOnClickListener(l: OnClickListener?) {
        super.setOnClickListener(l)
        // If read-only, forward click to the edit text so it triggers the parent click
        if (!editText.isFocusable) {
            editText.setOnClickListener(l)
        }
    }
}
