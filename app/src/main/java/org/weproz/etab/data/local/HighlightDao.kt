package org.weproz.etab.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HighlightDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(highlight: HighlightEntity): Long

    @Query("SELECT * FROM highlights WHERE bookPath = :bookPath")
    suspend fun getHighlightsForBook(bookPath: String): List<HighlightEntity>

    @Query("SELECT * FROM highlights WHERE bookPath = :bookPath AND chapterIndex = :chapterIndex")
    suspend fun getHighlightsForChapter(bookPath: String, chapterIndex: Int): List<HighlightEntity>

    @Query("DELETE FROM highlights WHERE bookPath = :bookPath AND chapterIndex = :chapterIndex AND rangeData = :rangeData")
    suspend fun deleteHighlight(bookPath: String, chapterIndex: Int, rangeData: String)

    @Delete
    suspend fun delete(highlight: HighlightEntity)
}
