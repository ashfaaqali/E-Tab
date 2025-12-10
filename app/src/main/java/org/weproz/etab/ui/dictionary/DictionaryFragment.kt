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

        loadWordsOfTheDay()
    }

    private fun loadWordsOfTheDay() {
        lifecycleScope.launch {
            val dao = org.weproz.etab.data.local.WordDatabase.getDatabase(requireContext()).wordDao()
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
