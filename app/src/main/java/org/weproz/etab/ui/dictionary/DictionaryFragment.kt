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

        loadWordOfTheDay()
    }

    private fun loadWordOfTheDay() {
        lifecycleScope.launch {
            val dao = org.weproz.etab.data.local.WordDatabase.getDatabase(requireContext()).wordDao()
            val word = dao.getRandomWord()
            word?.let {
                binding.textWodWord.text = it.word
                binding.textWodType.text = it.wordType
                binding.textWodDefinition.text = it.definition
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
