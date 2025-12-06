package org.weproz.etab.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "text_notes")
data class TextNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val color: Int, // For card background
    val updatedAt: Long = System.currentTimeMillis()
)
