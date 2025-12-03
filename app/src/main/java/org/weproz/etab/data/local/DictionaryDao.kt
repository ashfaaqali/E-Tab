package org.weproz.etab.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DictionaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(words: List<DictionaryEntity>)

    @Query("SELECT * FROM dictionary WHERE word = :word COLLATE NOCASE LIMIT 1")
    suspend fun getDefinition(word: String): DictionaryEntity?

    @Query("SELECT * FROM dictionary WHERE word LIKE :query || '%' LIMIT 20")
    suspend fun getSuggestions(query: String): List<DictionaryEntity>

    @Query("SELECT * FROM dictionary ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomWord(): DictionaryEntity?
    
    @Query("SELECT COUNT(*) FROM dictionary")
    suspend fun getCount(): Int
}
