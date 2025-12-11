package org.weproz.etab.ui.notes.whiteboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.weproz.etab.data.local.WhiteboardEntity
import org.weproz.etab.data.repository.NoteRepository
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@HiltViewModel
class WhiteboardEditorViewModel @Inject constructor(
    private val repository: NoteRepository
) : ViewModel() {

    fun saveWhiteboard(
        whiteboardId: Long,
        currentTitle: String,
        dataPath: String,
        pagesJson: String,
        filesDir: File,
        onComplete: (Long, String, String) -> Unit // returns newId, newTitle, newDataPath
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val filename = "wb_${System.currentTimeMillis()}.json"
            
            // Determine file to write to
            val actualFile = if (dataPath.isNotEmpty()) {
                File(dataPath)
            } else {
                File(filesDir, filename)
            }
            
            FileOutputStream(actualFile).use { it.write(pagesJson.toByteArray()) }
            
            val newDataPath = actualFile.absolutePath

            // Generate title if empty (new whiteboard)
            val titleToSave = if (currentTitle.isEmpty()) {
                val count = repository.getWhiteboardCount()
                "Untitled ${count + 1}"
            } else {
                currentTitle
            }

            val entity = WhiteboardEntity(
                id = if (whiteboardId == -1L) 0 else whiteboardId,
                title = titleToSave,
                thumbnailPath = null, // TODO: Generate thumbnail
                dataPath = newDataPath,
                updatedAt = System.currentTimeMillis()
            )
            
            val newId = if (whiteboardId == -1L) {
               repository.insertWhiteboard(entity)
            } else {
               repository.updateWhiteboard(entity)
               whiteboardId
            }
            
            withContext(Dispatchers.Main) {
                onComplete(newId, titleToSave, newDataPath)
            }
        }
    }

    fun loadWhiteboardData(dataPath: String, onLoaded: (String) -> Unit, onError: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(dataPath)
                if (!file.exists()) return@launch
                
                val json = file.readText()
                withContext(Dispatchers.Main) {
                    onLoaded(json)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onError()
                }
            }
        }
    }
}
