package org.weproz.etab.ui.books

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.weproz.etab.data.local.BookEntity
import org.weproz.etab.data.local.BookType
import org.weproz.etab.databinding.FragmentBooksBinding
import org.weproz.etab.ui.reader.PdfReaderActivity
import org.weproz.etab.ui.reader.ReaderActivity
import org.weproz.etab.ui.search.DefinitionDialogFragment
import org.weproz.etab.util.ShareHelper

class BooksFragment : Fragment() {

    private var _binding: FragmentBooksBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var viewModel: BooksViewModel

    private val adapter = BookAdapter(
        onBookClick = { book ->
            viewModel.onBookOpened(book)
            // Route to appropriate reader based on book type
            val intent = when (book.type) {
                BookType.PDF -> Intent(requireContext(), PdfReaderActivity::class.java)
                BookType.EPUB -> Intent(requireContext(), ReaderActivity::class.java)
            }
            intent.putExtra("book_path", book.path)
            startActivity(intent)
        },
        onFavoriteClick = { book ->
            viewModel.toggleFavorite(book)
        },
        onBookLongClick = { book ->
            showBookContextMenu(book)
        },
        onDictionaryClick = { entry ->
            DefinitionDialogFragment.newInstance(entry).show(parentFragmentManager, "definition")
        },
        onFolderClick = { path ->
            viewModel.openFolder(path)
        }
    )

    private fun showBookContextMenu(book: BookEntity) {
        val options = arrayOf(
            if (book.isFavorite) "Remove from Favorites" else "Add to Favorites",
            "Share via Bluetooth",
            "Delete Book"
        )

        org.weproz.etab.ui.custom.CustomDialog(requireContext())
            .setTitle(book.title)
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> viewModel.toggleFavorite(book)
                    1 -> ShareHelper.shareBookViaBluetooth(requireContext(), book.path, book.title)
                    2 -> showDeleteConfirmation(book)
                }
            }
            .show()
    }

    private fun showDeleteConfirmation(book: BookEntity) {
        org.weproz.etab.ui.custom.CustomDialog(requireContext())
            .setTitle("Delete Book")
            .setMessage("Are you sure you want to delete '${book.title}'? This will remove the file from your device.")
            .setPositiveButton("Delete") { dialog ->
                viewModel.deleteBook(book)
                Toast.makeText(requireContext(), "Book deleted", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel")
            .show()
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                viewModel.refresh()
            } else {
                Toast.makeText(requireContext(), "Permission needed to list books", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onResume() {
        super.onResume()
        if (hasStoragePermission()) {
            viewModel.refresh()
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBooksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!viewModel.closeFolder()) {
                    isEnabled = false
                    requireActivity().onBackPressed()
                }
            }
        })
        
        val factory = BooksViewModelFactory(requireActivity().application)
        viewModel = androidx.lifecycle.ViewModelProvider(this, factory)[BooksViewModel::class.java]

        val layoutManager = GridLayoutManager(requireContext(), 2)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return when (adapter.getItemViewType(position)) {
                    0 -> 1 // Book (Grid)
                    1 -> 2 // Dictionary (List/Full width)
                    else -> 1
                }
            }
        }
        binding.recyclerBooks.layoutManager = layoutManager
        binding.recyclerBooks.adapter = adapter
        binding.recyclerBooks.itemAnimator = null // Disable animations to prevent flickering
        
        binding.searchBar.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s.toString())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        
        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                viewModel.setTab(tab?.position ?: 0)
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        lifecycleScope.launch {
            viewModel.items.collect { items ->
                adapter.submitList(items)
            }
        }
        
        lifecycleScope.launch {
             viewModel.currentTab.collect { index ->
                 if (binding.tabLayout.selectedTabPosition != index) {
                     binding.tabLayout.getTabAt(index)?.select()
                 }
             }
        }

        lifecycleScope.launch {
            viewModel.currentFolder.collect { folder ->
                if (folder != null) {
                    binding.layoutFolderHeader.visibility = View.VISIBLE
                    binding.textFolderPath.text = java.io.File(folder).name
                    
                    // Adjust constraints or visibility if needed
                    // For now, layout handles it via GONE/VISIBLE
                } else {
                    binding.layoutFolderHeader.visibility = View.GONE
                }
            }
        }
        
        binding.btnFolderBack.setOnClickListener {
            viewModel.closeFolder()
        }

        checkPermissionAndLoad()
    }

    private fun checkPermissionAndLoad() {
        if (!hasStoragePermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = Uri.parse(String.format("package:%s", requireContext().packageName))
                    startActivityForResult(intent, 2296)
                } catch (e: Exception) {
                    val intent = Intent()
                    intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                    startActivityForResult(intent, 2296)
                }
            } else {
                requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        } else {
            viewModel.refresh()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2296) {
            if (hasStoragePermission()) {
                viewModel.refresh()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
