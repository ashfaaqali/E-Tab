package org.weproz.etab.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Type converters for Room to handle custom types
 */
class Converters {
    @TypeConverter
    fun fromBookType(value: BookType): String = value.name

    @TypeConverter
    fun toBookType(value: String): BookType = BookType.valueOf(value)
}

@Database(
    entities = [HighlightEntity::class, TextNoteEntity::class, WhiteboardEntity::class, BookEntity::class],
    version = 7,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun highlightDao(): HighlightDao
    abstract fun textNoteDao(): TextNoteDao
    abstract fun whiteboardDao(): WhiteboardDao
    abstract fun bookDao(): BookDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "etab_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

