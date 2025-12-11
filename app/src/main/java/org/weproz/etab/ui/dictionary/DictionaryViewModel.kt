package org.weproz.etab.ui.dictionary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.weproz.etab.data.local.DictionaryEntry
import org.weproz.etab.data.repository.DictionaryRepository
import javax.inject.Inject

@HiltViewModel
class DictionaryViewModel @Inject constructor(
    private val repository: DictionaryRepository
) : ViewModel() {

    private val _wordsOfTheDay = MutableStateFlow<List<DictionaryEntry>>(emptyList())
    val wordsOfTheDay: StateFlow<List<DictionaryEntry>> = _wordsOfTheDay.asStateFlow()

    private val _saveWordStatus = MutableStateFlow<Result<Unit>?>(null)
    val saveWordStatus: StateFlow<Result<Unit>?> = _saveWordStatus.asStateFlow()

    fun loadWordsOfTheDay() {
        viewModelScope.launch {
            try {
                val words = repository.getRandomWords()
                _wordsOfTheDay.value = words
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun saveWord(word: String, type: String, definition: String) {
        viewModelScope.launch {
            try {
                val id = kotlin.random.Random.nextInt()
                val entry = DictionaryEntry(
                    id = id,
                    word = word,
                    wordType = type,
                    definition = definition
                )
                repository.insertWord(entry)
                _saveWordStatus.value = Result.success(Unit)
            } catch (e: Exception) {
                _saveWordStatus.value = Result.failure(e)
            }
        }
    }
    
    fun resetSaveStatus() {
        _saveWordStatus.value = null
    }
}
