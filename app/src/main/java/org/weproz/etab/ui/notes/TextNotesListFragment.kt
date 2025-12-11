package org.weproz.etab.ui.notes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.weproz.etab.databinding.FragmentTextNotesListBinding
import org.weproz.etab.util.ShareHelper

@AndroidEntryPoint
class TextNotesListFragment : Fragment() {

    private var _binding: FragmentTextNotesListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TextNotesViewModel by viewModels()

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
                val options = arrayOf("Rename", "Share as PDF", "Delete")
                org.weproz.etab.ui.custom.CustomDialog(requireContext())
                    .setTitle("Options")
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> { // Rename
                                val input = android.widget.EditText(requireContext())
                                input.setText(note.title)
                                org.weproz.etab.ui.custom.CustomDialog(requireContext())
                                    .setTitle("Rename Note")
                                    .setView(input)
                                    .setPositiveButton("Rename") { dialog ->
                                        viewModel.renameTextNote(note, input.text.toString())
                                        dialog.dismiss()
                                    }
                                    .setNegativeButton("Cancel")
                                    .show()
                            }
                            1 -> { // Share as PDF
                                viewLifecycleOwner.lifecycleScope.launch {
                                    ShareHelper.shareTextNoteAsPdf(requireContext(), note)
                                }
                            }
                            2 -> { // Delete
                                org.weproz.etab.ui.custom.CustomDialog(requireContext())
                                    .setTitle("Delete Note")
                                    .setMessage("Are you sure you want to delete '${note.title}'?")
                                    .setPositiveButton("Delete") { dialog ->
                                        viewModel.deleteTextNote(note)
                                        dialog.dismiss()
                                    }
                                    .setNegativeButton("Cancel")
                                    .show()
                            }
                        }
                    }
                    .show()
            }
        )
        binding.recyclerTextNotes.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.textNotes.collectLatest { notes ->
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
