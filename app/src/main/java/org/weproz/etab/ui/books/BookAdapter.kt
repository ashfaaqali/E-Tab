package org.weproz.etab.ui.books

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.weproz.etab.R
import org.weproz.etab.data.local.BookEntity
import kotlinx.coroutines.launch

class BookAdapter(
    private val onBookClick: (BookEntity) -> Unit,
    private val onFavoriteClick: (BookEntity) -> Unit,
    private val onBookLongClick: (BookEntity) -> Unit
) : RecyclerView.Adapter<BookAdapter.ViewHolder>() {

    private var books: List<BookEntity> = emptyList()

    fun submitList(newBooks: List<BookEntity>) {
        books = newBooks
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(books[position])
    }

    override fun getItemCount(): Int = books.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.text_book_title)
        private val lastOpenedView: TextView = itemView.findViewById(R.id.text_last_opened)
        private val coverView: android.widget.ImageView = itemView.findViewById(R.id.image_cover)
        private val favButton: android.widget.ImageButton = itemView.findViewById(R.id.btn_favorite)

        fun bind(book: BookEntity) {
            titleView.text = book.title
            
            if (book.lastOpened > 0) {
                val diff = System.currentTimeMillis() - book.lastOpened
                val hours = diff / (1000 * 60 * 60)
                val days = hours / 24
                lastOpenedView.text = when {
                    days > 0 -> "Opened $days days ago"
                    hours > 0 -> "Opened $hours hours ago"
                    else -> "Opened just now"
                }
            } else {
                lastOpenedView.text = "Never opened"
            }
            
            if (book.isFavorite) {
                favButton.setColorFilter(android.graphics.Color.parseColor("#FFD700")) // Gold
                favButton.setImageResource(android.R.drawable.btn_star_big_on)
            } else {
                favButton.clearColorFilter()
                favButton.setImageResource(android.R.drawable.btn_star_big_off)
            }
            
            favButton.setOnClickListener { onFavoriteClick(book) }
            itemView.setOnClickListener { onBookClick(book) }
            itemView.setOnLongClickListener {
                onBookLongClick(book)
                true
            }
            
            // Basic Cover Loading
            coverView.setImageDrawable(null) // Reset
            coverView.tag = book.path // Tag for concurrency check if we were using async
            
            // For MVP: Simple placeholder or load logic. 
            // Loading cover from EPUB is expensive. We should do it async.
            // Using a simple drawable for now as default.
            coverView.setBackgroundColor(if (adapterPosition % 2 == 0) android.graphics.Color.LTGRAY else android.graphics.Color.GRAY)
            
        }
        
        private fun loadCoverAsync(path: String, target: android.widget.ImageView) {
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val epubReader = nl.siegmann.epublib.epub.EpubReader()
                    // Using readEpub instead of readEpubLazy which caused compilation issues
                    val book = epubReader.readEpub(java.io.FileInputStream(path))
                    val coverImage = book.coverImage
                    if (coverImage != null) {
                         val bitmap = android.graphics.BitmapFactory.decodeStream(coverImage.inputStream)
                         kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                             if (target.tag == path) {
                                 target.setImageBitmap(bitmap)
                                 target.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                             }
                         }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
