package org.autojs.autojs.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.autojs.autoxjs.R
import org.autojs.autoxjs.databinding.OptionListViewBinding
import org.autojs.autoxjs.databinding.OperationDialogItemBinding

class OptionListView(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    interface OnItemClickListener {
        fun onItemClick(position: Int, id: Int)
    }

    private val ids = ArrayList<Int>()
    private val icons = ArrayList<Int>()
    private val texts = ArrayList<String>()
    private var listener: OnItemClickListener? = null

    private val binding = OptionListViewBinding.inflate(LayoutInflater.from(context), this, true)

    init {
        binding.list.layoutManager = LinearLayoutManager(context)
        binding.list.adapter = Adapter()
    }

    fun setOnItemClickListener(listener: OnItemClickListener) {
        this.listener = listener
    }

    class Builder(private val context: Context) {
        private val view = OptionListView(context)

        fun item(id: Int, iconRes: Int, textRes: Int): Builder {
            return item(id, iconRes, context.getString(textRes))
        }

        fun item(id: Int, iconRes: Int, text: String): Builder {
            view.ids.add(id)
            view.icons.add(iconRes)
            view.texts.add(text)
            return this
        }

        fun title(title: String): Builder {
            view.binding.title.visibility = VISIBLE
            view.binding.title.text = title
            return this
        }

        fun title(title: Int): Builder {
            return title(context.getString(title))
        }

        fun bindItemClick(listener: OnItemClickListener): Builder {
            view.setOnItemClickListener(listener)
            return this
        }

        fun build(): OptionListView {
            return view
        }
    }

    private inner class Adapter : RecyclerView.Adapter<ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = OperationDialogItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.binding.icon.setImageResource(icons[position])
            holder.binding.text.text = texts[position]
            holder.itemView.id = ids[position]
            holder.itemView.setOnClickListener {
                listener?.onItemClick(position, ids[position])
            }
        }

        override fun getItemCount(): Int = ids.size
    }

    private class ViewHolder(val binding: OperationDialogItemBinding) : RecyclerView.ViewHolder(binding.root)
}