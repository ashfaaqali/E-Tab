package org.weproz.etab.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.weproz.etab.data.local.TextNoteEntity
import org.weproz.etab.data.repository.NoteRepository
import javax.inject.Inject

@HiltViewModel
class TextNotesViewModel @Inject constructor(
    private val repository: NoteRepository
) : ViewModel() {

    val textNotes: Flow<List<TextNoteEntity>> = repository.getAllTextNotes()

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
