package org.weproz.etab.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val path: String,
    val title: String,
    val size: Long,
    val isFavorite: Boolean = false,
    val lastOpened: Long = 0,
    val coverPath: String? = null
)
