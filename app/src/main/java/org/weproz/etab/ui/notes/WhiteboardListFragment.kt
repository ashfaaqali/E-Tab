package org.weproz.etab.ui.notes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.weproz.etab.databinding.FragmentWhiteboardListBinding
import org.weproz.etab.util.ShareHelper

@AndroidEntryPoint
class WhiteboardListFragment : Fragment() {

    private var _binding: FragmentWhiteboardListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: WhiteboardViewModel by viewModels()

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
        
        val adapter = WhiteboardAdapter(
            onItemClick = { whiteboard ->
                val file = java.io.File(whiteboard.dataPath)
                if (file.exists()) {
                    val intent = android.content.Intent(requireContext(), org.weproz.etab.ui.notes.whiteboard.WhiteboardEditorActivity::class.java).apply {
                        putExtra("whiteboard_id", whiteboard.id)
                        putExtra("whiteboard_title", whiteboard.title)
                        putExtra("whiteboard_data_path", whiteboard.dataPath)
                    }
                    startActivity(intent)
                } else {
                    org.weproz.etab.ui.custom.CustomDialog(requireContext())
                        .setTitle("File Not Found")
                        .setMessage("The whiteboard file '${whiteboard.title}' could not be found. It may have been deleted externally. Do you want to remove it from the list?")
                        .setPositiveButton("Remove") { dialog ->
                            viewModel.deleteWhiteboard(whiteboard)
                            dialog.dismiss()
                        }
                        .setNegativeButton("Cancel")
                        .show()
                }
            },
            onItemLongClick = { whiteboard ->
                val options = arrayOf("Rename", "Share as PDF", "Delete")

                org.weproz.etab.ui.custom.CustomDialog(requireContext())
                    .setTitle("Options")
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> { // Rename
                                val input = android.widget.EditText(requireContext())
                                input.setText(whiteboard.title)
                                org.weproz.etab.ui.custom.CustomDialog(requireContext())
                                    .setTitle("Rename Whiteboard")
                                    .setView(input)
                                    .setPositiveButton("Rename") { dialog ->
                                        viewModel.renameWhiteboard(whiteboard, input.text.toString())
                                        dialog.dismiss()
                                    }
                                    .setNegativeButton("Cancel")
                                    .show()
                            }
                            1 -> { // Share as PDF
                                viewLifecycleOwner.lifecycleScope.launch {
                                    ShareHelper.shareWhiteboardAsPdf(
                                        requireContext(),
                                        whiteboard.dataPath,
                                        whiteboard.title
                                    )
                                }
                            }
                            2 -> { // Delete
                                org.weproz.etab.ui.custom.CustomDialog(requireContext())
                                    .setTitle("Delete Whiteboard")
                                    .setMessage("Are you sure you want to delete '${whiteboard.title}'?")
                                    .setPositiveButton("Delete") { dialog ->
                                        viewModel.deleteWhiteboard(whiteboard)
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
        binding.recyclerWhiteboards.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.whiteboards.collectLatest { whiteboards ->
                adapter.submitList(whiteboards)
            }
        }

        binding.fabAddWhiteboard.setOnClickListener {
            val input = android.widget.EditText(requireContext())
            input.hint = "Whiteboard Title"
            
            org.weproz.etab.ui.custom.CustomDialog(requireContext())
                .setTitle("New Whiteboard")
                .setView(input)
                .setPositiveButton("Create") { dialog ->
                    val title = input.text.toString().trim()
                    if (title.isNotEmpty()) {
                        val intent = android.content.Intent(requireContext(), org.weproz.etab.ui.notes.whiteboard.WhiteboardEditorActivity::class.java).apply {
                            putExtra("whiteboard_title", title)
                        }
                        startActivity(intent)
                        dialog.dismiss()
                    } else {
                        Toast.makeText(requireContext(), "Title cannot be empty", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel")
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
