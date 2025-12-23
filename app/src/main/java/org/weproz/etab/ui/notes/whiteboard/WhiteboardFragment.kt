package org.weproz.etab.ui.notes.whiteboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.weproz.etab.data.local.database.AppDatabase
import org.weproz.etab.data.local.entity.WhiteboardEntity
import org.weproz.etab.data.model.whiteboard.GridType
import org.weproz.etab.data.model.whiteboard.ParsedPage
import org.weproz.etab.data.serializer.WhiteboardSerializer
import org.weproz.etab.databinding.FragmentWhiteboardEditorBinding
import java.io.File
import java.io.FileOutputStream

class WhiteboardFragment : Fragment() {

    private var _binding: FragmentWhiteboardEditorBinding? = null
    private val binding get() = _binding!!

    private var dataPath: String? = null
    private var initialTitle: String? = null
    
    // Multi-page support
    private val pages = mutableListOf<ParsedPage>()
    private var currentPageIndex = 0

    // Mutex to prevent concurrent saves causing duplicates
    private val saveMutex = Mutex()
    
    companion object {
        private const val ARG_DATA_PATH = "arg_data_path"
        private const val ARG_TITLE = "arg_title"

        fun newInstance(dataPath: String, title: String? = null): WhiteboardFragment {
            val fragment = WhiteboardFragment()
            val args = Bundle()
            args.putString(ARG_DATA_PATH, dataPath)
            if (title != null) {
                args.putString(ARG_TITLE, title)
            }
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            dataPath = it.getString(ARG_DATA_PATH)
            initialTitle = it.getString(ARG_TITLE)
        }
        
        // Initialize with at least one empty page
        if (pages.isEmpty()) {
            pages.add(ParsedPage(emptyList(), GridType.NONE))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWhiteboardEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTools()
        setupPageNavigation()
        
        binding.whiteboardView.onActionCompleted = {
            saveWhiteboard()
        }
        
        if (dataPath != null) {
            loadWhiteboardData()
        } else {
            updatePageIndicator()
        }
    }

    override fun onPause() {
        super.onPause()
        saveCurrentPage()
        saveWhiteboard()
    }

    private fun setupTools() {
        binding.whiteboardToolbar.attachTo(binding.whiteboardView)
        
        binding.btnSave.setOnClickListener {
            saveCurrentPage()
            saveWhiteboard()
            android.widget.Toast.makeText(requireContext(), "Saved", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    

    
    private fun setupPageNavigation() {
        binding.btnPrevPage.setOnClickListener {
            if (currentPageIndex > 0) {
                saveCurrentPage()
                currentPageIndex--
                loadCurrentPage()
            }
        }
        
        binding.btnNextPage.setOnClickListener {
            if (currentPageIndex < pages.size - 1) {
                saveCurrentPage()
                currentPageIndex++
                loadCurrentPage()
            }
        }
        
        binding.btnAddPage.setOnClickListener {
            saveCurrentPage()
            pages.add(ParsedPage(emptyList(), GridType.NONE))
            currentPageIndex = pages.size - 1
            loadCurrentPage()
            android.widget.Toast.makeText(requireContext(), "New page added", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun saveCurrentPage() {
        if (currentPageIndex < pages.size) {
            val actions = binding.whiteboardView.getPaths().toList() // Create a copy!
            val gridType = binding.whiteboardView.gridType
            pages[currentPageIndex] = ParsedPage(actions, gridType)
            android.util.Log.d("WhiteboardFragment", "Saved page $currentPageIndex with ${actions.size} actions")
        }
    }
    
    private fun loadCurrentPage() {
        if (currentPageIndex < pages.size) {
            val page = pages[currentPageIndex]
            binding.whiteboardView.gridType = page.gridType
            binding.whiteboardView.loadPaths(page.actions)
            updatePageIndicator()
            android.util.Log.d("WhiteboardFragment", "Loaded page $currentPageIndex with ${page.actions.size} actions")
        }
    }
    
    private fun updatePageIndicator() {
        val pageNum = currentPageIndex + 1
        val totalPages = pages.size
        binding.textPageIndicator.text = "Page $pageNum of $totalPages"
        
        binding.btnPrevPage.isEnabled = currentPageIndex > 0
        binding.btnNextPage.isEnabled = currentPageIndex < pages.size - 1
        
        binding.btnPrevPage.alpha = if (currentPageIndex > 0) 1.0f else 0.3f
        binding.btnNextPage.alpha = if (currentPageIndex < pages.size - 1) 1.0f else 0.3f
    }

    private fun createNewDocument() {
        // Save current state first
        saveCurrentPage()
        saveWhiteboard()
        
        // Create backup of current
        if (dataPath != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val currentFile = File(dataPath!!)
                    if (currentFile.exists()) {
                        val backupName = "${currentFile.nameWithoutExtension}_backup_${System.currentTimeMillis()}.json"
                        val backupFile = File(currentFile.parent, backupName)
                        currentFile.copyTo(backupFile, overwrite = true)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                
                withContext(Dispatchers.Main) {
                    // Clear all pages and start fresh
                    pages.clear()
                    pages.add(ParsedPage(emptyList(), GridType.NONE))
                    currentPageIndex = 0
                    loadCurrentPage()
                    android.widget.Toast.makeText(requireContext(), "New document created (Previous saved)", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun saveWhiteboard() {
        if (dataPath == null) return
        
        saveCurrentPage() // Ensure current page is saved
        
        // Check if there's any content to save
        val hasContent = pages.any { page -> page.actions.isNotEmpty() }
        if (!hasContent) {
            android.util.Log.d("WhiteboardFragment", "No content to save, skipping database save")
            return
        }

        // Capture dataPath to avoid null issues in coroutine
        val currentDataPath = dataPath ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            // Use mutex to prevent concurrent saves causing race conditions
            saveMutex.withLock {
                val json = WhiteboardSerializer.serialize(pages)

                val file = File(currentDataPath)
                file.parentFile?.mkdirs()

                FileOutputStream(file).use { it.write(json.toByteArray()) }

                // Save to database using insertOrIgnore to prevent duplicates
                try {
                    val ctx = context ?: return@withLock
                    val dao = AppDatabase.getDatabase(ctx).whiteboardDao()

                    // First try to update if exists
                    val existingWhiteboard = dao.getWhiteboardByDataPath(currentDataPath)

                    if (existingWhiteboard != null) {
                        // Already exists - just update timestamp
                        dao.updateTimestampByDataPath(currentDataPath, System.currentTimeMillis())
                        android.util.Log.d("WhiteboardFragment", "Updated existing whiteboard: ${existingWhiteboard.id}")
                    } else {
                        // New entry - use insertOrIgnore which won't fail on duplicate dataPath
                        val count = dao.getWhiteboardCount()
                        val title = initialTitle ?: "Untitled ${count + 1}"

                        val newEntity = WhiteboardEntity(
                            title = title,
                            thumbnailPath = null,
                            dataPath = currentDataPath,
                            updatedAt = System.currentTimeMillis()
                        )
                        val id = dao.insertOrIgnore(newEntity)
                        if (id != -1L) {
                            android.util.Log.d("WhiteboardFragment", "Inserted new whiteboard: $id with title: $title")
                        } else {
                            // Insert was ignored (duplicate), just update timestamp
                            dao.updateTimestampByDataPath(currentDataPath, System.currentTimeMillis())
                            android.util.Log.d("WhiteboardFragment", "Insert ignored (duplicate), updated timestamp instead")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WhiteboardFragment", "Failed to save whiteboard to database", e)
                }
            }
        }
    }
    
    private fun loadWhiteboardData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (dataPath == null) return@launch
                val file = File(dataPath!!)
                if (!file.exists()) {
                    withContext(Dispatchers.Main) {
                        updatePageIndicator()
                    }
                    return@launch
                }
                
                val json = file.readText()
                val parsedData = WhiteboardSerializer.deserialize(json)
                
                withContext(Dispatchers.Main) {
                    pages.clear()
                    pages.addAll(parsedData.pages)
                    
                    if (pages.isEmpty()) {
                        pages.add(ParsedPage(emptyList(), GridType.NONE))
                    }
                    
                    currentPageIndex = 0
                    loadCurrentPage()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    updatePageIndicator()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
