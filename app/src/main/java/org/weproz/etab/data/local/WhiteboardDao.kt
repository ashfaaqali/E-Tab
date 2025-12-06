package org.weproz.etab.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WhiteboardDao {
    @Query("SELECT * FROM whiteboards ORDER BY updatedAt DESC")
    fun getAllWhiteboards(): Flow<List<WhiteboardEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(whiteboard: WhiteboardEntity): Long

    @Update
    suspend fun update(whiteboard: WhiteboardEntity)

    @Delete
    suspend fun delete(whiteboard: WhiteboardEntity)
}
