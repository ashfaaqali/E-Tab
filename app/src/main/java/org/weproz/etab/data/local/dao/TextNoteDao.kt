package org.weproz.etab.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.weproz.etab.data.local.entity.TextNoteEntity

@Dao
interface TextNoteDao {
    @Query("SELECT * FROM text_notes ORDER BY updatedAt DESC")
    fun getAllNotes(): Flow<List<TextNoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: TextNoteEntity): Long

    @Update
    suspend fun update(note: TextNoteEntity)

    @Delete
    suspend fun delete(note: TextNoteEntity)
}
