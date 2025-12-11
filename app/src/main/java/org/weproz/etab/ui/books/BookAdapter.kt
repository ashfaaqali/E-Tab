package org.weproz.etab.ui.books

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
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

    // In-memory cache for cover bitmaps to prevent reloading
    companion object {
        private val coverCache: LruCache<String, Bitmap> = LruCache(50) // Cache up to 50 covers
    }

    fun submitList(newBooks: List<BookEntity>) {
        val diffCallback = BookDiffCallback(books, newBooks)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        books = newBooks
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(books[position])
    }

    override fun getItemCount(): Int = books.size

    private class BookDiffCallback(
        private val oldList: List<BookEntity>,
        private val newList: List<BookEntity>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].path == newList[newItemPosition].path
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }

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
            
            // Set tag for concurrency check
            coverView.tag = book.path

            // Check if cover is already cached
            val cachedBitmap = coverCache.get(book.path)
            if (cachedBitmap != null) {
                // Use cached cover - no flickering
                coverView.setImageBitmap(cachedBitmap)
                coverView.scaleType = ImageView.ScaleType.CENTER_CROP
                coverView.setBackgroundColor(Color.TRANSPARENT)
                return
            }

            // Set placeholder and load cover
            coverView.setImageResource(R.drawable.app_logo)
            coverView.scaleType = ImageView.ScaleType.CENTER_INSIDE

            when (book.type) {
                BookType.PDF -> {
                    coverView.setBackgroundColor(Color.parseColor("#E53935")) // Red for PDF
                    loadPdfCoverAsync(book.path, coverView)
                }
                BookType.EPUB -> {
                    coverView.setBackgroundColor(Color.WHITE)
                    loadEpubCoverAsync(book.path, coverView)
                }
            }
        }
        
        private fun loadPdfCoverAsync(path: String, target: ImageView) {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    // Check cache again (might have been loaded by another coroutine)
                    if (coverCache.get(path) != null) {
                        withContext(Dispatchers.Main) {
                            if (target.tag == path) {
                                target.setImageBitmap(coverCache.get(path))
                                target.scaleType = ImageView.ScaleType.CENTER_CROP
                                target.setBackgroundColor(Color.TRANSPARENT)
                            }
                        }
                        return@launch
                    }

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
                        
                        // Cache the bitmap
                        coverCache.put(path, bitmap)

                        withContext(Dispatchers.Main) {
                            if (target.tag == path) {
                                target.setImageBitmap(bitmap)
                                target.scaleType = ImageView.ScaleType.CENTER_CROP
                                target.setBackgroundColor(Color.TRANSPARENT)
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
                    // Check cache again
                    if (coverCache.get(path) != null) {
                        withContext(Dispatchers.Main) {
                            if (target.tag == path) {
                                target.setImageBitmap(coverCache.get(path))
                                target.scaleType = ImageView.ScaleType.CENTER_CROP
                                target.setBackgroundColor(Color.TRANSPARENT)
                            }
                        }
                        return@launch
                    }

                    val epubReader = nl.siegmann.epublib.epub.EpubReader()
                    val book = epubReader.readEpub(FileInputStream(path))
                    val coverImage = book.coverImage
                    if (coverImage != null) {
                        val bitmap = android.graphics.BitmapFactory.decodeStream(coverImage.inputStream)

                        // Cache the bitmap
                        if (bitmap != null) {
                            coverCache.put(path, bitmap)
                        }

                        withContext(Dispatchers.Main) {
                            if (target.tag == path && bitmap != null) {
                                target.setImageBitmap(bitmap)
                                target.scaleType = ImageView.ScaleType.CENTER_CROP
                                target.setBackgroundColor(Color.TRANSPARENT)
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

