package org.weproz.etab.data.repository

import org.weproz.etab.data.local.AppDatabase
import org.weproz.etab.data.local.TextNoteEntity
import org.weproz.etab.data.local.WhiteboardEntity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoteRepository @Inject constructor(private val db: AppDatabase) {

    suspend fun deleteTextNote(note: TextNoteEntity) {
        db.textNoteDao().delete(note)
    }

    suspend fun renameTextNote(note: TextNoteEntity, newTitle: String) {
        val updated = note.copy(title = newTitle)
        db.textNoteDao().update(updated)
    }

    fun getAllTextNotes() = db.textNoteDao().getAllNotes()

    suspend fun deleteWhiteboard(whiteboard: WhiteboardEntity) {
        db.whiteboardDao().delete(whiteboard)
        // Also delete files
        if (whiteboard.dataPath.isNotEmpty()) {
            File(whiteboard.dataPath).delete()
        }
        if (whiteboard.thumbnailPath != null) {
            File(whiteboard.thumbnailPath).delete()
        }
    }

    suspend fun renameWhiteboard(whiteboard: WhiteboardEntity, newTitle: String) {
        val updated = whiteboard.copy(title = newTitle)
        db.whiteboardDao().update(updated)
    }

    fun getAllWhiteboards() = db.whiteboardDao().getAllWhiteboards()

    suspend fun getWhiteboardCount(): Int {
        return db.whiteboardDao().getWhiteboardCount()
    }

    suspend fun insertWhiteboard(whiteboard: WhiteboardEntity): Long {
        return db.whiteboardDao().insert(whiteboard)
    }

    suspend fun updateWhiteboard(whiteboard: WhiteboardEntity) {
        db.whiteboardDao().update(whiteboard)
    }
}
