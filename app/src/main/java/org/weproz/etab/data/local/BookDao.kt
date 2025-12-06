package org.weproz.etab.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastOpened DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE isFavorite = 1 ORDER BY lastOpened DESC")
    fun getFavoriteBooks(): Flow<List<BookEntity>>
    
    @Query("SELECT * FROM books WHERE path = :path")
    suspend fun getBook(path: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(book: BookEntity): Long

    @Query("UPDATE books SET isFavorite = :isFavorite WHERE path = :path")
    suspend fun updateFavorite(path: String, isFavorite: Boolean)

    @Query("UPDATE books SET lastOpened = :timestamp WHERE path = :path")
    suspend fun updateLastOpened(path: String, timestamp: Long)

    @Query("DELETE FROM books WHERE path = :path")
    suspend fun delete(path: String)
}
