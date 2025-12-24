package org.weproz.etab.ui.books

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.weproz.etab.data.local.database.AppDatabase
import org.weproz.etab.data.local.entity.BookEntity
import org.weproz.etab.data.local.entity.DictionaryEntry
import org.weproz.etab.data.repository.BookRepository
import java.io.File

sealed class SearchItem {
    data class BookItem(val book: BookEntity) : SearchItem()
    data class DictionaryItem(val entry: DictionaryEntry) : SearchItem()
    data class FolderItem(val path: String, val name: String, val count: Int) : SearchItem()
}

class BooksViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: BookRepository
    
    // 0 = All, 1 = Favorites, 2 = Folders
    private val _currentTab = MutableStateFlow(0) 
    val currentTab = _currentTab.asStateFlow()

    private val _currentFolder = MutableStateFlow<String?>(null)
    val currentFolder = _currentFolder.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _items = MutableStateFlow<List<SearchItem>>(emptyList())
    val items = _items.asStateFlow()

    init {
        val database = AppDatabase.getDatabase(application)
        repository = BookRepository(application, database.bookDao())
        
        // Initial sync
        refresh()
        
        val booksFlow = combine(
            repository.allBooks, 
            repository.favoriteBooks, 
            _currentTab,
            _currentFolder
        ) { all, favs, tab, folder ->
            Triple(all, favs, Pair(tab, folder))
        }

        viewModelScope.launch {
            combine(
                booksFlow,
                _searchQuery
            ) { (all, favs, state), query ->
                val (tab, folder) = state
                
                if (query.isNotEmpty()) {
                    // Search mode: Search across ALL books regardless of tab/folder
                    val filteredBooks = all.filter { 
                        it.title.contains(query, ignoreCase = true) 
                    }.map { SearchItem.BookItem(it) }
                    return@combine filteredBooks
                }

                when (tab) {
                    0 -> { // All Books
                        all.map { SearchItem.BookItem(it) }
                    }
                    1 -> { // Favorites
                        favs.map { SearchItem.BookItem(it) }
                    }
                    2 -> { // Folders
                        if (folder == null) {
                            // List Folders
                            val folders = all.groupBy { File(it.path).parentFile?.absolutePath ?: "" }
                                .filter { it.key.isNotEmpty() }
                                .map { (path, books) ->
                                    val name = File(path).name
                                    SearchItem.FolderItem(path, name, books.size)
                                }
                                .sortedBy { it.name }
                            folders
                        } else {
                            // List Books in Folder
                            all.filter { 
                                val parent = File(it.path).parentFile?.absolutePath
                                parent == folder 
                            }.map { SearchItem.BookItem(it) }
                        }
                    }
                    else -> emptyList()
                }
            }.collect {
                _items.value = it
            }
        }
    }
    
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }
    
    fun refresh() {
        viewModelScope.launch {
            repository.syncBooks()
        }
    }

    fun setTab(index: Int) {
        _currentTab.value = index
        _currentFolder.value = null // Reset folder navigation when switching tabs
    }

    fun openFolder(path: String) {
        _currentFolder.value = path
    }

    fun closeFolder(): Boolean {
        if (_currentFolder.value != null) {
            _currentFolder.value = null
            return true
        }
        return false
    }

    fun toggleFavorite(book: BookEntity) {
        viewModelScope.launch {
            repository.toggleFavorite(book.path, book.isFavorite)
        }
    }

    fun onBookOpened(book: BookEntity) {
        viewModelScope.launch {
            repository.updateLastOpened(book.path)
        }
    }

    fun deleteBook(book: BookEntity) {
        viewModelScope.launch {
            repository.deleteBook(book.path)
        }
    }
}

class BooksViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BooksViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BooksViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
