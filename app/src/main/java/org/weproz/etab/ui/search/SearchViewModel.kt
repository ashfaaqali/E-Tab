package org.weproz.etab.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.weproz.etab.data.local.DictionaryEntry
import org.weproz.etab.data.repository.DictionaryRepository
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: DictionaryRepository
) : ViewModel() {

    private val _suggestions = MutableStateFlow<List<DictionaryEntry>>(emptyList())
    val suggestions: StateFlow<List<DictionaryEntry>> = _suggestions.asStateFlow()

    private var searchJob: Job? = null

    fun search(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300) // Debounce
            if (query.isNotEmpty()) {
                val results = repository.getSuggestions(query)
                _suggestions.value = results
            } else {
                _suggestions.value = emptyList()
            }
        }
    }
}
