package org.weproz.etab.ui.notes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.weproz.etab.data.local.AppDatabase
import org.weproz.etab.databinding.FragmentTextNotesListBinding

class TextNotesListFragment : Fragment() {

    private var _binding: FragmentTextNotesListBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTextNotesListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val repo = org.weproz.etab.data.repository.NoteRepository(requireContext())

        val adapter = TextNoteAdapter(
            onItemClick = { note ->
                val intent = android.content.Intent(requireContext(), NoteEditorActivity::class.java).apply {
                    putExtra("note_id", note.id)
                    putExtra("note_title", note.title)
                    putExtra("note_content", note.content)
                }
                startActivity(intent)
            },
            onItemLongClick = { note ->
                val options = arrayOf("Rename", "Delete")
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> { // Rename
                                val input = android.widget.EditText(requireContext())
                                input.setText(note.title)
                                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                    .setTitle("Rename Note")
                                    .setView(input)
                                    .setPositiveButton("Rename") { _, _ ->
                                        viewLifecycleOwner.lifecycleScope.launch {
                                            repo.renameTextNote(note, input.text.toString())
                                        }
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                            1 -> { // Delete
                                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                    .setTitle("Delete Note")
                                    .setMessage("Are you sure you want to delete '${note.title}'?")
                                    .setPositiveButton("Delete") { _, _ ->
                                        viewLifecycleOwner.lifecycleScope.launch {
                                            repo.deleteTextNote(note)
                                        }
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                        }
                    }
                    .show()
            }
        )
        binding.recyclerTextNotes.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.getDatabase(requireContext())
            db.textNoteDao().getAllNotes().collectLatest { notes ->
                adapter.submitList(notes)
            }
        }

        binding.fabAddNote.setOnClickListener {
             val intent = android.content.Intent(requireContext(), NoteEditorActivity::class.java)
             startActivity(intent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
