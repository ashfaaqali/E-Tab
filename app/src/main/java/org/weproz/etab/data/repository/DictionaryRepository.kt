package org.weproz.etab.data.repository

import org.weproz.etab.data.local.entity.DictionaryEntry
import org.weproz.etab.data.local.database.WordDatabase
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DictionaryRepository @Inject constructor(
    private val database: WordDatabase
) {
    suspend fun insertWord(entry: DictionaryEntry) {
        database.wordDao().insert(entry)
    }

    suspend fun getRandomWords(): List<DictionaryEntry> {
        return database.wordDao().getRandomWords()
    }

    suspend fun getSuggestions(query: String): List<DictionaryEntry> {
        return database.wordDao().getSuggestions(query)
    }
}
