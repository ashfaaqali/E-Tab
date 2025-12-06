package org.weproz.etab.ui.search

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.weproz.etab.data.local.AppDatabase
import org.weproz.etab.databinding.ActivitySearchBinding

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private val adapter = SuggestionAdapter { entity ->
        DefinitionDialogFragment.newInstance(entity).show(supportFragmentManager, "definition")
    }
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.recyclerSuggestions.layoutManager = LinearLayoutManager(this)
        binding.recyclerSuggestions.adapter = adapter

        binding.searchInput.requestFocus()

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(300) // Debounce
                    val query = s.toString()
                    if (query.isNotEmpty()) {
                        val dao = org.weproz.etab.data.local.WordDatabase.getDatabase(this@SearchActivity).wordDao()
                        val suggestions = dao.getSuggestions(query)
                        adapter.submitList(suggestions)
                    } else {
                        adapter.submitList(emptyList())
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }
}
