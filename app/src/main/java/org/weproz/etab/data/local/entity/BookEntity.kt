package org.weproz.etab.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Enum representing the type of book/document
 */
enum class BookType {
    EPUB,
    PDF
}

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val path: String,
    val title: String,
    val size: Long,
    val type: BookType = BookType.EPUB,
    val isFavorite: Boolean = false,
    val lastOpened: Long = 0,
    val coverPath: String? = null,
    val lastReadPage: Int = 0  // Track last read page/chapter
)
