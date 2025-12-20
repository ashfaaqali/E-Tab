package org.weproz.etab.data.repository

import org.weproz.etab.data.model.whiteboard.DrawAction
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WhiteboardRepository @Inject constructor() {
    
    private val _actions = mutableListOf<DrawAction>()
    
    fun getActions(): List<DrawAction> = _actions.toList()
    
    fun addAction(action: DrawAction) {
        _actions.add(action)
    }
    
    fun clearActions() {
        _actions.clear()
    }
}
