package org.autojs.autojs.plugin

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import org.autojs.autoxjs.R

/**
 * 插件列表适配器。
 */
class PluginListAdapter(
    private val onToggle: (PluginMetaInfo, Boolean) -> Unit,
    private val onOpenSettings: (PluginMetaInfo) -> Unit
) : RecyclerView.Adapter<PluginListAdapter.ViewHolder>() {

    private val items = mutableListOf<PluginMetaInfo>()

    fun submit(list: List<PluginMetaInfo>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_plugin, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val title: TextView = itemView.findViewById(R.id.text_title)
        private val subtitle: TextView = itemView.findViewById(R.id.text_subtitle)
        private val description: TextView = itemView.findViewById(R.id.text_description)
        private val switch: SwitchCompat = itemView.findViewById(R.id.switch_enabled)

        fun bind(meta: PluginMetaInfo) {
            title.text = meta.label
            subtitle.text = itemView.context.getString(
                R.string.plugin_version_format,
                meta.packageName,
                meta.versionName
            )
            description.text = meta.description.orEmpty()
            description.visibility =
                if (meta.description.isNullOrBlank()) View.GONE else View.VISIBLE

            switch.setOnCheckedChangeListener(null)
            switch.isChecked = meta.enabled
            switch.setOnCheckedChangeListener { _, isChecked ->
                onToggle(meta, isChecked)
            }

            itemView.setOnClickListener {
                if (meta.settingsComponent != null) {
                    onOpenSettings(meta)
                }
            }
        }
    }
}
