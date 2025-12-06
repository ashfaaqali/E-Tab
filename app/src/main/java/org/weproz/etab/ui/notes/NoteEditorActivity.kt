package org.weproz.etab.ui.notes

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.weproz.etab.data.local.AppDatabase
import org.weproz.etab.data.local.TextNoteEntity
import org.weproz.etab.databinding.ActivityNoteEditorBinding

class NoteEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNoteEditorBinding
    private var noteId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoteEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        noteId = intent.getLongExtra("note_id", -1)
        val title = intent.getStringExtra("note_title")
        val content = intent.getStringExtra("note_content")

        if (noteId != -1L) {
            binding.editTitle.setText(title)
            binding.editContent.setText(content)
            binding.toolbar.title = "Edit Note"
        } else {
            binding.toolbar.title = "New Note"
        }

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        binding.fabSave.setOnClickListener {
            saveNote()
        }
    }

    private fun saveNote() {
        val title = binding.editTitle.text.toString().trim()
        val content = binding.editContent.text.toString().trim()

        if (title.isEmpty() && content.isEmpty()) {
            Toast.makeText(this, "Empty note discarded", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val dao = AppDatabase.getDatabase(this@NoteEditorActivity).textNoteDao()
            val note = TextNoteEntity(
                id = if (noteId == -1L) 0 else noteId,
                title = title,
                content = content,
                color = 0 // Default color for now
            )
            
            if (noteId == -1L) {
                dao.insert(note)
            } else {
                dao.update(note)
            }
            
            finish()
        }
    }
}
