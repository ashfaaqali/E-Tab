package org.weproz.etab.ui.notes

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.weproz.etab.data.local.TextNoteEntity
import org.weproz.etab.databinding.ItemTextNoteBinding

class TextNoteAdapter(
    private val onItemClick: (TextNoteEntity) -> Unit,
    private val onItemLongClick: (TextNoteEntity) -> Unit
) : ListAdapter<TextNoteEntity, TextNoteAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTextNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
    }

    inner class ViewHolder(private val binding: ItemTextNoteBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(note: TextNoteEntity) {
            binding.textNoteTitle.text = note.title
            binding.textNotePreview.text = note.content
            binding.root.setOnClickListener { onItemClick(note) }
            binding.root.setOnLongClickListener { 
                onItemLongClick(note)
                true
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<TextNoteEntity>() {
        override fun areItemsTheSame(oldItem: TextNoteEntity, newItem: TextNoteEntity): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: TextNoteEntity, newItem: TextNoteEntity): Boolean = oldItem == newItem
    }
}
