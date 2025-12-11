package org.weproz.etab.ui.notes

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.weproz.etab.R
import org.weproz.etab.data.local.WhiteboardEntity
import org.weproz.etab.databinding.ItemWhiteboardBinding

class WhiteboardAdapter(
    private val onItemClick: (WhiteboardEntity) -> Unit,
    private val onItemLongClick: (WhiteboardEntity) -> Unit
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

            // Set whiteboard logo on white background
            binding.imageWbThumbnail.setBackgroundColor(Color.WHITE)
            //binding.imageWbThumbnail.setImageResource(R.drawable.whiteboard_logo)
            binding.imageWbThumbnail.scaleType = ImageView.ScaleType.CENTER_INSIDE

            binding.root.setOnClickListener { onItemClick(whiteboard) }
            binding.root.setOnLongClickListener {
                onItemLongClick(whiteboard)
                true
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<WhiteboardEntity>() {
        override fun areItemsTheSame(oldItem: WhiteboardEntity, newItem: WhiteboardEntity): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: WhiteboardEntity, newItem: WhiteboardEntity): Boolean = oldItem == newItem
    }
}
