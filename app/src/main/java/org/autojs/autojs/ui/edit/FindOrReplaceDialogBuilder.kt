package org.autojs.autojs.ui.edit

import android.content.Context
import android.preference.PreferenceManager
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import androidx.core.widget.addTextChangedListener
import com.afollestad.materialdialogs.MaterialDialog
import org.autojs.autoxjs.R
import org.autojs.autojs.theme.dialog.ThemeColorMaterialDialogBuilder
import org.autojs.autojs.ui.edit.editor.CodeEditor
import org.autojs.autoxjs.databinding.DialogFindOrReplaceBinding
import androidx.core.content.edit

class FindOrReplaceDialogBuilder(
    context: Context,
    private val editorView: EditorView
) : ThemeColorMaterialDialogBuilder(context) {

    private val binding = DialogFindOrReplaceBinding.inflate(
        LayoutInflater.from(context),
        null,
        false
    )

    init {
        setupViews()
        restoreState()
        autoDismiss(false)
        onNegative { dialog, _ -> dialog.dismiss() }
        onPositive { dialog, _ ->
            storeState()
            findOrReplace(dialog)
        }

        binding.checkboxReplaceAll.setOnCheckedChangeListener { _, _ ->
            syncWithReplaceCheckBox()
        }

        binding.replacement.addTextChangedListener {
            onTextChanged()
        }
    }

    private fun setupViews() {
        customView(binding.root, true)
        positiveText(R.string.ok)
        negativeText(R.string.cancel)
        title(R.string.text_find_or_replace)
    }

    private fun storeState() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit {
                putString(KEY_KEYWORDS, binding.keywords.text.toString())
            }
    }

    private fun restoreState() {
        binding.keywords.setText(
            PreferenceManager.getDefaultSharedPreferences(context)
                .getString(KEY_KEYWORDS, "")
        )
    }

    private fun syncWithReplaceCheckBox() {
        if (binding.checkboxReplaceAll.isChecked && !binding.checkboxReplace.isChecked) {
            binding.checkboxReplace.isChecked = true
        }
    }

    private fun onTextChanged() {
        if ((binding.replacement.text?.length ?: 0) > 0) {
            binding.checkboxReplace.isChecked = true
        }
    }

    private fun findOrReplace(dialog: MaterialDialog) {
        val keywords = binding.keywords.text.toString()
        if (keywords.isEmpty()) {
            return
        }
        try {
            val usingRegex = binding.checkboxRegex.isChecked
            if (!binding.checkboxReplace.isChecked) {
                editorView.find(keywords, usingRegex)
            } else {
                val replacement = binding.replacement.text.toString()
                if (binding.checkboxReplaceAll.isChecked) {
                    editorView.replaceAll(keywords, replacement, usingRegex)
                } else {
                    editorView.replace(keywords, replacement, usingRegex)
                }
            }
            dialog.dismiss()
        } catch (e: CodeEditor.CheckedPatternSyntaxException) {
            e.printStackTrace()
            binding.keywords.error = context.getString(R.string.error_pattern_syntax)
        }
    }

    fun setQueryIfNotEmpty(s: String): FindOrReplaceDialogBuilder {
        if (!TextUtils.isEmpty(s)) {
            binding.keywords.setText(s)
        }
        return this
    }

    companion object {
        private const val KEY_KEYWORDS = "..."
    }
}