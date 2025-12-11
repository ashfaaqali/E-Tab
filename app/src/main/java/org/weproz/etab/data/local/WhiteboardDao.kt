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

    @Query("SELECT * FROM whiteboards")
    suspend fun getAllWhiteboardsList(): List<WhiteboardEntity>

    @Query("SELECT * FROM whiteboards WHERE dataPath = :dataPath LIMIT 1")
    suspend fun getWhiteboardByDataPath(dataPath: String): WhiteboardEntity?

    @Query("SELECT COUNT(*) FROM whiteboards")
    suspend fun getWhiteboardCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(whiteboard: WhiteboardEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(whiteboard: WhiteboardEntity): Long

    @Update
    suspend fun update(whiteboard: WhiteboardEntity)

    @Delete
    suspend fun delete(whiteboard: WhiteboardEntity)

    @Query("UPDATE whiteboards SET updatedAt = :updatedAt WHERE dataPath = :dataPath")
    suspend fun updateTimestampByDataPath(dataPath: String, updatedAt: Long)
}
