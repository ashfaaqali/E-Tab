package org.weproz.etab.data.repository

import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.weproz.etab.data.local.BookDao
import org.weproz.etab.data.local.BookEntity
import java.io.File

class BookRepository(private val context: Context, private val bookDao: BookDao) {

    val allBooks = bookDao.getAllBooks()
    val favoriteBooks = bookDao.getFavoriteBooks()

    suspend fun syncBooks() {
        withContext(Dispatchers.IO) {
            val fileBooks = findEpubFiles()
            
            // 1. Insert new books if they don't exist (IGNORE strategy handles duplicates)
            // But we want to preserve metadata if it exists.
            fileBooks.forEach { fileBook ->
                // Check if exists
                val existing = bookDao.getBook(fileBook.path)
                if (existing == null) {
                    bookDao.insert(fileBook)
                }
            }
            
            // 2. Remove books from DB that are no longer on disk
            // (Optional: depending on if we want to keep history for deleted files. 
            // For now, let's keep it simple and clean up.)
            // Fetch all from DB (one-shot, not flow) - requires a new DAO method or just collecting flow?
            // For simplicity/perf, we might skip this or implement a getPaths() query.
            // Let's implement robust sync later if needed. For now, we ensure new files appear.
        }
    }

    suspend fun toggleFavorite(path: String, currentStatus: Boolean) {
        bookDao.updateFavorite(path, !currentStatus)
    }

    suspend fun updateLastOpened(path: String) {
        bookDao.updateLastOpened(path, System.currentTimeMillis())
    }

    suspend fun deleteBook(path: String) {
        bookDao.delete(path)
        // Optionally delete file?
        // val file = File(path)
        // if (file.exists()) file.delete()
        // For now, let's keep it safe and only delete from APP DB, 
        // OR we can offer choice. 
        // User request "delete and all those options" implies full delete.
        // Let's deleting from DB only for now to be safe, or if File Scanning runs again it will reappear?
        // If we delete from DB but file exists, syncBooks() will re-add it.
        // So we MUST delete the file or mark it as "hidden".
        // Let's delete the file too.
        val file = File(path)
        if (file.exists()) {
            file.delete()
        }
    }

    private fun findEpubFiles(): List<BookEntity> {
        val books = mutableListOf<BookEntity>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE
        )
        
        val selection = "${MediaStore.Files.FileColumns.DATA} LIKE ?"
        val selectionArgs = arrayOf("%.epub")

        val cursor = context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            selectionArgs,
            null
        )

        cursor?.use {
            val dataColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val nameColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)

            while (it.moveToNext()) {
                val path = it.getString(dataColumn)
                val name = it.getString(nameColumn)
                val size = it.getLong(sizeColumn)
                
                // Exclude hidden files or non-existent
                if (File(path).exists()) {
                     books.add(BookEntity(path, name, size))
                }
            }
        }
        return books
    }
}
