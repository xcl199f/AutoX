package org.autojs.autojs.ui.doc

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import com.afollestad.materialdialogs.MaterialDialog
import com.stardust.util.IntentUtil
import org.autojs.autoxjs.R
import org.autojs.autojs.ui.widget.EWebView
import org.autojs.autoxjs.databinding.FloatingManualDialogBinding

class ManualDialog(private val context: Context) {

    private val binding = FloatingManualDialogBinding.inflate(LayoutInflater.from(context))
    private val dialog: Dialog
    private var currentUrl: String = ""

    init {
        dialog = MaterialDialog.Builder(context)
                .customView(binding.root, false)
                .build()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    fun title(title: String): ManualDialog {
        binding.title.text = title
        return this
    }

    fun url(url: String): ManualDialog {
        currentUrl = url
        binding.ewebView.getWebView().loadUrl(url)
        return this
    }

    fun pinToLeft(listener: View.OnClickListener): ManualDialog {
        binding.pinToLeft.setOnClickListener {
            dialog.dismiss()
            listener.onClick(it)
        }
        return this
    }

    fun show(): ManualDialog {
        dialog.show()
        return this
    }

    fun close() {
        dialog.dismiss()
    }

    fun viewInNewActivity() {
        dialog.dismiss()
        IntentUtil.browse(context, currentUrl)
    }
}