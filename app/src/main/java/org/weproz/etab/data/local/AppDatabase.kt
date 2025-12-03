package org.weproz.etab.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [DictionaryEntity::class, HighlightEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dictionaryDao(): DictionaryDao
    abstract fun highlightDao(): HighlightDao

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
                .addCallback(DatabaseCallback(context))
                .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback(private val context: Context) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        populateDatabase(database.dictionaryDao())
                    }
                }
            }

            suspend fun populateDatabase(dao: DictionaryDao) {
                if (dao.getCount() == 0) {
                    val initialWords = listOf(
                        DictionaryEntity(word = "Serendipity", definition = "The occurrence and development of events by chance in a happy or beneficial way.", type = "noun", example = "It was pure serendipity that we met."),
                        DictionaryEntity(word = "Ephemeral", definition = "Lasting for a very short time.", type = "adjective", example = "Fashions are ephemeral, changing with every season."),
                        DictionaryEntity(word = "Luminous", definition = "Full of or shedding light; bright or shining, especially in the dark.", type = "adjective", example = "The luminous dial on his watch."),
                        DictionaryEntity(word = "Solitude", definition = "The state or situation of being alone.", type = "noun", example = "She savored her few hours of freedom and solitude."),
                        DictionaryEntity(word = "Aurora", definition = "A natural electrical phenomenon characterized by the appearance of streamers of reddish or greenish light in the sky.", type = "noun", example = "The aurora borealis."),
                        DictionaryEntity(word = "Melancholy", definition = "A feeling of pensive sadness, typically with no obvious cause.", type = "noun", example = "An air of melancholy surrounded him."),
                        DictionaryEntity(word = "Euphoria", definition = "A feeling or state of intense excitement and happiness.", type = "noun", example = "The euphoria of success."),
                        DictionaryEntity(word = "Resilience", definition = "The capacity to recover quickly from difficulties; toughness.", type = "noun", example = "The often remarkable resilience of so many British institutions."),
                        DictionaryEntity(word = "Eloquent", definition = "Fluent or persuasive in speaking or writing.", type = "adjective", example = "An eloquent speech."),
                        DictionaryEntity(word = "Ponder", definition = "Think about (something) carefully, especially before making a decision or reaching a conclusion.", type = "verb", example = "I pondered the question of what clothes to wear for the occasion.")
                    )
                    dao.insertAll(initialWords)
                }
            }
        }
    }
}
