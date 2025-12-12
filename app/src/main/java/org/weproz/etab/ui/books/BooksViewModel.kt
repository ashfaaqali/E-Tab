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
import org.weproz.etab.data.local.AppDatabase
import org.weproz.etab.data.local.BookEntity
import org.weproz.etab.data.local.DictionaryEntry
import org.weproz.etab.data.local.WordDatabase
import org.weproz.etab.data.repository.BookRepository
import org.weproz.etab.data.repository.DictionaryRepository

import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

sealed class SearchItem {
    data class BookItem(val book: BookEntity) : SearchItem()
    data class DictionaryItem(val entry: DictionaryEntry) : SearchItem()
}

class BooksViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: BookRepository
    private val dictionaryRepository: DictionaryRepository
    
    // 0 = All, 1 = Favorites
    private val _currentTab = MutableStateFlow(0) 
    val currentTab = _currentTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _items = MutableStateFlow<List<SearchItem>>(emptyList())
    val items = _items.asStateFlow()

    init {
        val database = AppDatabase.getDatabase(application)
        repository = BookRepository(application, database.bookDao())
        
        val wordDb = WordDatabase.getDatabase(application)
        dictionaryRepository = DictionaryRepository(wordDb)
        
        // Initial sync
        refresh()
        
        val booksFlow = combine(
            repository.allBooks, 
            repository.favoriteBooks, 
            _currentTab
        ) { all, favs, tab ->
            if (tab == 1) favs else all
        }

        viewModelScope.launch {
            combine(
                booksFlow,
                _searchQuery
            ) { books, query ->
                Pair(books, query)
            }.flatMapLatest { (books, query) ->
                flow {
                    if (query.isEmpty()) {
                        emit(books.map { SearchItem.BookItem(it) })
                    } else {
                        // Filter books
                        val filteredBooks = books.filter { 
                            it.title.contains(query, ignoreCase = true) 
                        }.map { SearchItem.BookItem(it) }
                        
                        // Search dictionary
                        val dictionaryResults = dictionaryRepository.getSuggestions(query)
                            .map { SearchItem.DictionaryItem(it) }
                            
                        // Combine: Dictionary results on top
                        emit(dictionaryResults + filteredBooks)
                    }
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
            // Trigger sync to refresh list? 
            // Repository flows should update automatically if DAO emits.
            // But if we deleted file, we might want to ensure file system scan matches?
            // "getAllBooks" is flow from DB, so deleting from DAO updates UI. 
            // Correct.
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
