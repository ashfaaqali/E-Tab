package org.weproz.etab.data.repository

import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.weproz.etab.data.local.dao.BookDao
import org.weproz.etab.data.local.entity.BookEntity
import org.weproz.etab.data.local.entity.BookType
import java.io.File

class BookRepository(private val context: Context, private val bookDao: BookDao) {

    val allBooks = bookDao.getAllBooks()
    val favoriteBooks = bookDao.getFavoriteBooks()

    suspend fun syncBooks() {
        withContext(Dispatchers.IO) {
            val fileBooks = findBookFiles()
            
            // 1. Insert new books if they don't exist
            fileBooks.forEach { fileBook ->
                val existing = bookDao.getBook(fileBook.path)
                if (existing == null) {
                    bookDao.insert(fileBook)
                }
            }
            
            // 2. Remove books from DB that are no longer on disk
            val dbBooks = bookDao.getAllBooksList()
            dbBooks.forEach { dbBook ->
                val file = File(dbBook.path)
                if (!file.exists()) {
                    bookDao.delete(dbBook.path)
                }
            }
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

    /**
     * Finds all supported book files (EPUB and PDF) on the device
     */
    private fun findBookFiles(): List<BookEntity> {
        val books = mutableListOf<BookEntity>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE
        )
        
        // Query for both EPUB and PDF files
        val selection = "${MediaStore.Files.FileColumns.DATA} LIKE ? OR ${MediaStore.Files.FileColumns.DATA} LIKE ?"
        val selectionArgs = arrayOf("%.epub", "%.pdf")

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
                    // Determine book type based on file extension
                    val bookType = when {
                        path.lowercase().endsWith(".pdf") -> BookType.PDF
                        path.lowercase().endsWith(".epub") -> BookType.EPUB
                        else -> BookType.EPUB // Default fallback
                    }
                    books.add(BookEntity(path, name, size, bookType))
                }
            }
        }
        return books
    }
}
