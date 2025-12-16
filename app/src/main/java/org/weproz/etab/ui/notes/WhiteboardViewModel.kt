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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File

@HiltViewModel
class WhiteboardViewModel @Inject constructor(
    private val repository: NoteRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")

    val whiteboards: Flow<List<WhiteboardEntity>> = combine(
        repository.getAllWhiteboards(),
        _searchQuery
    ) { list, query ->
        val filteredList = if (query.isEmpty()) {
            list
        } else {
            list.filter { it.title.contains(query, ignoreCase = true) }
        }
        // Filter out whiteboards where the file does not exist
        filteredList.filter { File(it.dataPath).exists() }
    }
    .flowOn(Dispatchers.IO)

    init {
        syncWhiteboards()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private fun syncWhiteboards() {
        viewModelScope.launch(Dispatchers.IO) {
            val allWhiteboards = repository.getAllWhiteboardsList()
            allWhiteboards.forEach { wb ->
                if (!File(wb.dataPath).exists()) {
                    repository.deleteWhiteboard(wb)
                }
            }
        }
    }

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
