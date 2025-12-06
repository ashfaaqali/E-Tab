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
import org.weproz.etab.data.repository.BookRepository

class BooksViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: BookRepository
    
    // 0 = All, 1 = Favorites
    private val _currentTab = MutableStateFlow(0) 
    val currentTab = _currentTab.asStateFlow()

    private val _books = MutableStateFlow<List<BookEntity>>(emptyList())
    val books = _books.asStateFlow()

    init {
        val database = AppDatabase.getDatabase(application)
        repository = BookRepository(application, database.bookDao())
        
        // Initial sync
        viewModelScope.launch {
            repository.syncBooks()
        }
        
        // Observe data based on tab selection
        viewModelScope.launch {
            combine(repository.allBooks, repository.favoriteBooks, _currentTab) { all, favs, tab ->
                if (tab == 1) favs else all
            }.collect {
                _books.value = it
            }
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
