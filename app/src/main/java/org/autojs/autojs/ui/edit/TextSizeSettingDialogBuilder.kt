package org.autojs.autojs.ui.edit

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import com.afollestad.materialdialogs.MaterialDialog
import org.autojs.autoxjs.R
import org.autojs.autojs.theme.dialog.ThemeColorMaterialDialogBuilder
import org.autojs.autoxjs.databinding.DialogTextSizeSettingBinding

class TextSizeSettingDialogBuilder(context: Context) :
    ThemeColorMaterialDialogBuilder(context),
    SeekBar.OnSeekBarChangeListener {

    interface PositiveCallback {
        fun onPositive(value: Int)
    }

    private val binding = DialogTextSizeSettingBinding.inflate(
        LayoutInflater.from(context),
        null,
        false
    )

    private var textSize = 0
    private var materialDialog: MaterialDialog? = null

    init {
        customView(binding.root, false)
        title(R.string.text_text_size)
        positiveText(R.string.ok)
        negativeText(R.string.cancel)
        binding.seekbar.setOnSeekBarChangeListener(this)
    }

    private fun setTextSize(size: Int) {
        textSize = size
        val title = context.getString(R.string.text_size_current_value, size)
        if (materialDialog != null) {
            materialDialog?.setTitle(title)
        } else {
            title(title)
        }
        binding.previewText.textSize = size.toFloat()
    }

    fun initialValue(value: Int): TextSizeSettingDialogBuilder {
        binding.seekbar.progress = value - MIN
        return this
    }

    fun callback(callback: PositiveCallback): TextSizeSettingDialogBuilder {
        onPositive { _, _ -> callback.onPositive(textSize) }
        return this
    }

    override fun build(): MaterialDialog {
        materialDialog = super.build()
        return materialDialog!!
    }

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        setTextSize(progress + MIN)
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {}

    override fun onStopTrackingTouch(seekBar: SeekBar) {}

    companion object {
        private const val MIN = 2
    }
}