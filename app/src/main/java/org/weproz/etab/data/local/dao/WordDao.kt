package org.weproz.etab.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.weproz.etab.data.local.entity.DictionaryEntry

@Dao
interface WordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DictionaryEntry)

    @Query("SELECT * FROM entries WHERE word = :word COLLATE NOCASE LIMIT 1")
    suspend fun getDefinition(word: String): DictionaryEntry?

    @Query("SELECT * FROM entries WHERE word LIKE :query || '%' LIMIT 20")
    suspend fun getSuggestions(query: String): List<DictionaryEntry>

    @Query("SELECT * FROM entries ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomWord(): DictionaryEntry?

    @Query("SELECT * FROM entries ORDER BY RANDOM() LIMIT 3")
    suspend fun getRandomWords(): List<DictionaryEntry>
}
