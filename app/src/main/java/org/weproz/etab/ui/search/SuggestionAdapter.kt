package org.weproz.etab.ui.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.weproz.etab.R
import org.weproz.etab.data.local.entity.DictionaryEntry

class SuggestionAdapter(
    private val onItemClick: (DictionaryEntry) -> Unit
) : RecyclerView.Adapter<SuggestionAdapter.ViewHolder>() {

    private var items: List<DictionaryEntry> = emptyList()

    fun submitList(newItems: List<DictionaryEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_suggestion, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.text_word)

        fun bind(item: DictionaryEntry) {
            textView.text = item.word
            itemView.setOnClickListener { onItemClick(item) }
        }
    }
}
