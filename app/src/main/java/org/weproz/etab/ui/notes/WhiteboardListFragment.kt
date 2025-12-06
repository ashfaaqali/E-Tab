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
        
        val adapter = WhiteboardAdapter { whiteboard ->
            val intent = android.content.Intent(requireContext(), org.weproz.etab.ui.notes.whiteboard.WhiteboardEditorActivity::class.java).apply {
                putExtra("whiteboard_id", whiteboard.id)
                putExtra("whiteboard_title", whiteboard.title)
                putExtra("whiteboard_data_path", whiteboard.dataPath)
            }
            startActivity(intent)
        }
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
