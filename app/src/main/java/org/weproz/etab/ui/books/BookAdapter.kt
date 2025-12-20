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
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.weproz.etab.R
import org.weproz.etab.data.local.BookEntity
import org.weproz.etab.data.local.BookType
import org.weproz.etab.data.local.DictionaryEntry
import java.io.File
import java.io.FileInputStream

class BookAdapter(
    private val onBookClick: (BookEntity) -> Unit,
    private val onFavoriteClick: (BookEntity) -> Unit,
    private val onBookLongClick: (BookEntity) -> Unit,
    private val onDictionaryClick: (DictionaryEntry) -> Unit,
    private val onFolderClick: (String) -> Unit
) : ListAdapter<SearchItem, RecyclerView.ViewHolder>(ItemDiffCallback()) {

    companion object {
        private const val TYPE_BOOK = 0
        private const val TYPE_DICTIONARY = 1
        private const val TYPE_FOLDER = 2
        private val coverCache: LruCache<String, Bitmap> = LruCache(50)
        private const val PAYLOAD_TIMESTAMP = "PAYLOAD_TIMESTAMP"
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is SearchItem.BookItem -> TYPE_BOOK
            is SearchItem.DictionaryItem -> TYPE_DICTIONARY
            is SearchItem.FolderItem -> TYPE_FOLDER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_BOOK -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)
                BookViewHolder(view)
            }
            TYPE_DICTIONARY -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_suggestion, parent, false)
                DictionaryViewHolder(view)
            }
            TYPE_FOLDER -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false)
                FolderViewHolder(view)
            }
            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is SearchItem.BookItem -> (holder as BookViewHolder).bind(item.book)
            is SearchItem.DictionaryItem -> (holder as DictionaryViewHolder).bind(item.entry)
            is SearchItem.FolderItem -> (holder as FolderViewHolder).bind(item)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            val item = getItem(position)
            if (item is SearchItem.BookItem && holder is BookViewHolder) {
                for (payload in payloads) {
                    if (payload == PAYLOAD_TIMESTAMP) {
                        holder.updateTimestamp(item.book)
                    }
                }
            }
        }
    }

    private class ItemDiffCallback : DiffUtil.ItemCallback<SearchItem>() {
        override fun areItemsTheSame(oldItem: SearchItem, newItem: SearchItem): Boolean {
            return when {
                oldItem is SearchItem.BookItem && newItem is SearchItem.BookItem -> 
                    oldItem.book.path == newItem.book.path
                oldItem is SearchItem.DictionaryItem && newItem is SearchItem.DictionaryItem -> 
                    oldItem.entry.id == newItem.entry.id
                oldItem is SearchItem.FolderItem && newItem is SearchItem.FolderItem ->
                    oldItem.path == newItem.path
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: SearchItem, newItem: SearchItem): Boolean {
            return oldItem == newItem
        }

        override fun getChangePayload(oldItem: SearchItem, newItem: SearchItem): Any? {
            if (oldItem is SearchItem.BookItem && newItem is SearchItem.BookItem) {
                // Check if only timestamp changed
                if (oldItem.book.copy(lastOpened = newItem.book.lastOpened) == newItem.book) {
                    return PAYLOAD_TIMESTAMP
                }
            }
            return null
        }
    }

    inner class FolderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView: TextView = itemView.findViewById(R.id.text_folder_name)
        private val countView: TextView = itemView.findViewById(R.id.text_folder_count)

        fun bind(item: SearchItem.FolderItem) {
            nameView.text = item.name
            countView.text = "${item.count} items"
            itemView.setOnClickListener { onFolderClick(item.path) }
        }
    }

    inner class BookViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.text_book_title)
        private val lastOpenedView: TextView = itemView.findViewById(R.id.text_last_opened)
        private val coverView: ImageView = itemView.findViewById(R.id.image_cover)
        private val favButton: ImageButton = itemView.findViewById(R.id.btn_favorite)

        fun bind(book: BookEntity) {
            titleView.text = book.title
            updateTimestamp(book)
            
            if (book.isFavorite) {
                favButton.setColorFilter(Color.parseColor("#FFD700"))
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
                else -> {
                    // Handle other types or future types
                    coverView.setImageResource(R.drawable.books)
                }
            }
        }

        fun updateTimestamp(book: BookEntity) {
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
        }

        private fun loadPdfCoverAsync(path: String, target: ImageView) {
            val lifecycleOwner = itemView.findViewTreeLifecycleOwner() ?: return
            lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
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
            val lifecycleOwner = itemView.findViewTreeLifecycleOwner() ?: return
            lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
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

    inner class DictionaryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.text_word)

        fun bind(item: DictionaryEntry) {
            textView.text = item.word
            itemView.setOnClickListener { onDictionaryClick(item) }
        }
    }
}


