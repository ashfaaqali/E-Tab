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
import org.weproz.etab.databinding.FragmentWhiteboardListBinding

class WhiteboardListFragment : Fragment() {

    private var _binding: FragmentWhiteboardListBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWhiteboardListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val repo = org.weproz.etab.data.repository.NoteRepository(requireContext())
        
        val adapter = WhiteboardAdapter(
            onItemClick = { whiteboard ->
                val intent = android.content.Intent(requireContext(), org.weproz.etab.ui.notes.whiteboard.WhiteboardEditorActivity::class.java).apply {
                    putExtra("whiteboard_id", whiteboard.id)
                    putExtra("whiteboard_title", whiteboard.title)
                    putExtra("whiteboard_data_path", whiteboard.dataPath)
                }
                startActivity(intent)
            },
            onItemLongClick = { whiteboard ->
                val options = arrayOf("Rename", "Delete", "Save as PDF", "Save as Image (TODO)") // TODO: Implement background save without opening?
                // Saving as PDF/Image usually requires rendering the View, which isn't easy from a background list context without opening logic.
                // The user requirements said "Save as pdf and save as image... on whiteboard list screen on long pressing".
                // To do this robustly without opening the editor, we would need to deserialize the data (Paths) and draw them onto a Canvas/Bitmap in memory.
                
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> { // Rename
                                val input = android.widget.EditText(requireContext())
                                input.setText(whiteboard.title)
                                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                    .setTitle("Rename Whiteboard")
                                    .setView(input)
                                    .setPositiveButton("Rename") { _, _ ->
                                        viewLifecycleOwner.lifecycleScope.launch {
                                            repo.renameWhiteboard(whiteboard, input.text.toString())
                                        }
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                            1 -> { // Delete
                                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                    .setTitle("Delete Whiteboard")
                                    .setMessage("Are you sure you want to delete '${whiteboard.title}'?")
                                    .setPositiveButton("Delete") { _, _ ->
                                        viewLifecycleOwner.lifecycleScope.launch {
                                            repo.deleteWhiteboard(whiteboard)
                                        }
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                            2 -> {
                                Toast.makeText(requireContext(), "To export, please open the whiteboard.", Toast.LENGTH_LONG).show()
                            }
                            3 -> {
                                Toast.makeText(requireContext(), "To export, please open the whiteboard.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .show()
            }
        )
        binding.recyclerWhiteboards.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.getDatabase(requireContext())
            db.whiteboardDao().getAllWhiteboards().collectLatest { whiteboards ->
                adapter.submitList(whiteboards)
            }
        }

        binding.fabAddWhiteboard.setOnClickListener {
             val intent = android.content.Intent(requireContext(), org.weproz.etab.ui.notes.whiteboard.WhiteboardEditorActivity::class.java)
             startActivity(intent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
