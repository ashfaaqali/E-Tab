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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.weproz.etab.data.local.BookEntity
import org.weproz.etab.databinding.FragmentBooksBinding
import org.weproz.etab.ui.reader.ReaderActivity
import java.io.File

class BooksFragment : Fragment() {

    private var _binding: FragmentBooksBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var viewModel: BooksViewModel

    private val adapter = BookAdapter(
        onBookClick = { book ->
            viewModel.onBookOpened(book)
            val intent = Intent(requireContext(), ReaderActivity::class.java)
            intent.putExtra("book_path", book.path)
            startActivity(intent)
        },
        onFavoriteClick = { book ->
            viewModel.toggleFavorite(book)
        },
        onBookLongClick = { book ->
            showBookContextMenu(book)
        }
    )

    private fun showBookContextMenu(book: BookEntity) {
        val options = arrayOf(
            if (book.isFavorite) "Remove from Favorites" else "Add to Favorites",
            "Delete Book"
        )

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(book.title)
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> viewModel.toggleFavorite(book)
                    1 -> showDeleteConfirmation(book)
                }
            }
            .show()
    }

    private fun showDeleteConfirmation(book: BookEntity) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete Book")
            .setMessage("Are you sure you want to delete '${book.title}'? This will remove the file from your device.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteBook(book)
                Toast.makeText(requireContext(), "Book deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
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
        
        val factory = BooksViewModelFactory(requireActivity().application)
        viewModel = androidx.lifecycle.ViewModelProvider(this, factory)[BooksViewModel::class.java]

        binding.recyclerBooks.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.recyclerBooks.adapter = adapter
        
        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                viewModel.setTab(tab?.position ?: 0)
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        lifecycleScope.launch {
            viewModel.books.collect { books ->
                adapter.submitList(books)
            }
        }
        
        lifecycleScope.launch {
             viewModel.currentTab.collect { index ->
                 if (binding.tabLayout.selectedTabPosition != index) {
                     binding.tabLayout.getTabAt(index)?.select()
                 }
             }
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
