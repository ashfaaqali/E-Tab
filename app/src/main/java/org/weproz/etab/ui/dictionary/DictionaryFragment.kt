package org.weproz.etab.ui.dictionary

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.weproz.etab.databinding.FragmentDictionaryBinding
import org.weproz.etab.ui.search.SearchActivity

class DictionaryFragment : Fragment() {

    private var _binding: FragmentDictionaryBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDictionaryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.searchBarTrigger.setOnClickListener {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }

        binding.btnAddWord.setOnClickListener {
            showAddWordDialog()
        }

        loadWordsOfTheDay()
    }

    private fun showAddWordDialog() {
        val context = requireContext()
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val wordInput = android.widget.EditText(context).apply {
            hint = "Word"
        }
        val typeInput = android.widget.EditText(context).apply {
            hint = "Type (e.g., noun, verb)"
        }
        val definitionInput = android.widget.EditText(context).apply {
            hint = "Definition"
        }


        layout.addView(wordInput)
        layout.addView(typeInput)
        layout.addView(definitionInput)

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Add New Word")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val word = wordInput.text.toString().trim()
                val type = typeInput.text.toString().trim()
                val definition = definitionInput.text.toString().trim()

                if (word.isNotEmpty() && definition.isNotEmpty()) {
                    saveWord(word, type, definition)
                } else {
                    android.widget.Toast.makeText(
                        context,
                        "Word and Definition are required",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveWord(word: String, type: String, definition: String) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val dao =
                    org.weproz.etab.data.local.WordDatabase.getDatabase(requireContext()).wordDao()
                // Generate a random ID to avoid conflicts if autoGenerate is not on
                val id = kotlin.random.Random.nextInt()
                val entry = org.weproz.etab.data.local.DictionaryEntry(
                    id = id,
                    word = word,
                    wordType = type,
                    definition = definition
                )
                dao.insert(entry)

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        requireContext(),
                        "Word saved!",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        requireContext(),
                        "Error saving word",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun loadWordsOfTheDay() {
        lifecycleScope.launch {
            val dao =
                org.weproz.etab.data.local.WordDatabase.getDatabase(requireContext()).wordDao()
            val words = dao.getRandomWords()

            // Populate Card 1
            words.getOrNull(0)?.let { word ->
                binding.textWord1.text = word.word.replaceFirstChar { it.uppercase() }
                binding.textType1.text = word.wordType
                binding.textDefinition1.text = word.definition
            }

            // Populate Card 2
            words.getOrNull(1)?.let { word ->
                binding.textWord2.text = word.word.replaceFirstChar { it.uppercase() }
                binding.textType2.text = word.wordType
                binding.textDefinition2.text = word.definition
            }

            // Populate Card 3
            words.getOrNull(2)?.let { word ->
                binding.textWord3.text = word.word.replaceFirstChar { it.uppercase() }
                binding.textType3.text = word.wordType
                binding.textDefinition3.text = word.definition
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
