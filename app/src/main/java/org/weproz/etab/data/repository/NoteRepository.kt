package org.weproz.etab.data.repository

import android.content.Context
import org.weproz.etab.data.local.AppDatabase
import org.weproz.etab.data.local.TextNoteEntity
import org.weproz.etab.data.local.WhiteboardEntity
import java.io.File

class NoteRepository(private val context: Context) {
    private val db = AppDatabase.getDatabase(context)

    suspend fun deleteTextNote(note: TextNoteEntity) {
        db.textNoteDao().delete(note)
    }

    suspend fun renameTextNote(note: TextNoteEntity, newTitle: String) {
        val updated = note.copy(title = newTitle)
        db.textNoteDao().update(updated)
    }

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
}
