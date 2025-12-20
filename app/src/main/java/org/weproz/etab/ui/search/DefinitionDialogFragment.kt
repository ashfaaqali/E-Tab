package org.weproz.etab.ui.search

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.DialogFragment
import org.weproz.etab.data.local.entity.DictionaryEntry
import org.weproz.etab.databinding.DialogDefinitionBinding

class DefinitionDialogFragment : DialogFragment() {

    private var _binding: DialogDefinitionBinding? = null
    private val binding get() = _binding!!

    private var word: String? = null
    private var definition: String? = null
    private var type: String? = null

    companion object {
        fun newInstance(entry: DictionaryEntry): DefinitionDialogFragment {
            val fragment = DefinitionDialogFragment()
            fragment.word = entry.word
            fragment.definition = entry.definition
            fragment.type = entry.wordType
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogDefinitionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.dialogWord.text = word
        binding.dialogDefinition.text = definition
        binding.dialogType.text = type ?: ""
        binding.dialogExample.visibility = View.GONE // New DB doesn't have examples

        binding.btnClose.setOnClickListener {
            dismiss()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(false)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
