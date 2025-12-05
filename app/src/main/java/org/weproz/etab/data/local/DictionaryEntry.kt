package org.weproz.etab.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "entries")
data class DictionaryEntry(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = 0,

    @ColumnInfo(name = "word")
    val word: String,

    @ColumnInfo(name = "wordtype")
    val wordType: String,

    @ColumnInfo(name = "definition")
    val definition: String
)
