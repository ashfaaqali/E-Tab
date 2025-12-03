package org.weproz.etab.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "dictionary", indices = [Index(value = ["word"], unique = true)])
data class DictionaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String,
    val definition: String,
    val type: String? = null, // e.g., "noun", "verb"
    val example: String? = null
)
