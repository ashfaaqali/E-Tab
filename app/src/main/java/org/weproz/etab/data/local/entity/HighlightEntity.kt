package org.weproz.etab.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "highlights")
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookPath: String,
    val chapterIndex: Int,
    val rangeData: String, // JSON or serialized range info
    val highlightedText: String,
    val color: Int,
    val createdAt: Long = System.currentTimeMillis()
)
