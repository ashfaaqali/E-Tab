package org.weproz.etab.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.weproz.etab.data.local.entity.TextNoteEntity
import org.weproz.etab.data.repository.NoteRepository
import javax.inject.Inject

@HiltViewModel
class TextNotesViewModel @Inject constructor(
    private val repository: NoteRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")

    val textNotes: Flow<List<TextNoteEntity>> = combine(
        repository.getAllTextNotes(),
        _searchQuery
    ) { notes, query ->
        if (query.isEmpty()) {
            notes
        } else {
            notes.filter { it.title.contains(query, ignoreCase = true) || it.content.contains(query, ignoreCase = true) }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun renameTextNote(note: TextNoteEntity, newTitle: String) {
        viewModelScope.launch {
            repository.renameTextNote(note, newTitle)
        }
    }

    fun deleteTextNote(note: TextNoteEntity) {
        viewModelScope.launch {
            repository.deleteTextNote(note)
        }
    }
}
