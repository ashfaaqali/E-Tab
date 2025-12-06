package org.weproz.etab.ui.notes

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.weproz.etab.data.local.WhiteboardEntity
import org.weproz.etab.databinding.ItemWhiteboardBinding

class WhiteboardAdapter(
    private val onItemClick: (WhiteboardEntity) -> Unit
) : ListAdapter<WhiteboardEntity, WhiteboardAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemWhiteboardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
    }

    inner class ViewHolder(private val binding: ItemWhiteboardBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(whiteboard: WhiteboardEntity) {
            binding.textWbTitle.text = whiteboard.title
            // TODO: Load thumbnail using Glide/Coil or manual bitmap loading
             binding.root.setOnClickListener { onItemClick(whiteboard) }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<WhiteboardEntity>() {
        override fun areItemsTheSame(oldItem: WhiteboardEntity, newItem: WhiteboardEntity): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: WhiteboardEntity, newItem: WhiteboardEntity): Boolean = oldItem == newItem
    }
}
