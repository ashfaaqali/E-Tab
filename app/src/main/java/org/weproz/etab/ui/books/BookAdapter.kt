package org.weproz.etab.ui.books

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.weproz.etab.R
import org.weproz.etab.data.local.BookEntity
import org.weproz.etab.data.local.BookType
import java.io.File
import java.io.FileInputStream

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
        private val coverView: ImageView = itemView.findViewById(R.id.image_cover)
        private val favButton: ImageButton = itemView.findViewById(R.id.btn_favorite)

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
                favButton.setColorFilter(Color.parseColor("#FFD700")) // Gold
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
            
            // Cover loading based on book type
            coverView.setImageDrawable(null) // Reset
            coverView.tag = book.path // Tag for concurrency check
            
            when (book.type) {
                BookType.PDF -> {
                    // Use a distinctive PDF color
                    coverView.setBackgroundColor(Color.parseColor("#E53935")) // Red for PDF
                    loadPdfCoverAsync(book.path, coverView)
                }
                BookType.EPUB -> {
                    // Use EPUB color scheme
                    val position = bindingAdapterPosition
                    coverView.setBackgroundColor(
                        if (position % 2 == 0) Color.parseColor("#1E88E5") // Blue
                        else Color.parseColor("#43A047") // Green
                    )
                    loadEpubCoverAsync(book.path, coverView)
                }
            }
        }
        
        private fun loadPdfCoverAsync(path: String, target: ImageView) {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val file = File(path)
                    if (!file.exists()) return@launch
                    
                    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = PdfRenderer(fd)
                    
                    if (renderer.pageCount > 0) {
                        val page = renderer.openPage(0)
                        
                        // Create thumbnail bitmap
                        val width = 200
                        val height = (200f * page.height / page.width).toInt()
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        
                        withContext(Dispatchers.Main) {
                            if (target.tag == path) {
                                target.setImageBitmap(bitmap)
                                target.scaleType = ImageView.ScaleType.CENTER_CROP
                            }
                        }
                    }
                    
                    renderer.close()
                    fd.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        private fun loadEpubCoverAsync(path: String, target: ImageView) {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val epubReader = nl.siegmann.epublib.epub.EpubReader()
                    val book = epubReader.readEpub(FileInputStream(path))
                    val coverImage = book.coverImage
                    if (coverImage != null) {
                        val bitmap = android.graphics.BitmapFactory.decodeStream(coverImage.inputStream)
                        withContext(Dispatchers.Main) {
                            if (target.tag == path) {
                                target.setImageBitmap(bitmap)
                                target.scaleType = ImageView.ScaleType.CENTER_CROP
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

