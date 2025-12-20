package org.weproz.etab.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.weproz.etab.data.local.dao.WordDao
import org.weproz.etab.data.local.entity.DictionaryEntry

@Database(entities = [DictionaryEntry::class], version = 3, exportSchema = false)
abstract class WordDatabase : RoomDatabase() {
    abstract fun wordDao(): WordDao

    companion object {
        @Volatile
        private var INSTANCE: WordDatabase? = null

        fun getDatabase(context: Context): WordDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WordDatabase::class.java,
                    "word_database"
                )
                .createFromAsset("dictionary.db")
                .fallbackToDestructiveMigration() // In case schema mismatch during dev
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
