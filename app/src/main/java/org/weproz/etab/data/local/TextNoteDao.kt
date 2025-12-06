package org.weproz.etab.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TextNoteDao {
    @Query("SELECT * FROM text_notes ORDER BY updatedAt DESC")
    fun getAllNotes(): Flow<List<TextNoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: TextNoteEntity)

    @Update
    suspend fun update(note: TextNoteEntity)

    @Delete
    suspend fun delete(note: TextNoteEntity)
}
