package org.weproz.etab.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.weproz.etab.data.local.AppDatabase
import org.weproz.etab.data.local.WhiteboardEntity
import org.weproz.etab.data.repository.NoteRepository
import javax.inject.Inject

@HiltViewModel
class WhiteboardViewModel @Inject constructor(
    private val repository: NoteRepository
) : ViewModel() {

    val whiteboards: Flow<List<WhiteboardEntity>> = repository.getAllWhiteboards()

    fun renameWhiteboard(whiteboard: WhiteboardEntity, newTitle: String) {
        viewModelScope.launch {
            repository.renameWhiteboard(whiteboard, newTitle)
        }
    }

    fun deleteWhiteboard(whiteboard: WhiteboardEntity) {
        viewModelScope.launch {
            repository.deleteWhiteboard(whiteboard)
        }
    }
}
