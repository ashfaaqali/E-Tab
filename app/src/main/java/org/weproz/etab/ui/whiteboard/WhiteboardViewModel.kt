package org.weproz.etab.ui.whiteboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.weproz.etab.data.model.whiteboard.DrawAction
import org.weproz.etab.data.repository.WhiteboardRepository
import javax.inject.Inject

@HiltViewModel
class WhiteboardViewModel @Inject constructor(
    private val repository: WhiteboardRepository
) : ViewModel() {

    private val _drawActions = MutableStateFlow<List<DrawAction>>(emptyList())
    val drawActions: StateFlow<List<DrawAction>> = _drawActions.asStateFlow()

    init {
        loadActions()
    }

    private fun loadActions() {
        _drawActions.value = repository.getActions()
    }

    fun addAction(action: DrawAction) {
        repository.addAction(action)
        loadActions()
    }

    fun clearCanvas() {
        repository.clearActions()
        loadActions()
    }
}
