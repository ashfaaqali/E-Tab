package org.weproz.etab.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "whiteboards")
data class WhiteboardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val thumbnailPath: String?, // Path to cached thumbnail image
    val dataPath: String, // Path to JSON file containing drawing paths
    val updatedAt: Long = System.currentTimeMillis()
)
