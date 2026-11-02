package org.autojs.autojs.plugin

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.autojs.autoxjs.R

/**
 * 插件列表：展示已安装的插件，支持启用/停用与打开插件设置。
 */
class PluginListActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: PluginListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plugin_list)
        applySystemBarAppearance()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        recycler = findViewById(R.id.recycler_plugins)
        emptyView = findViewById(R.id.text_empty)
        adapter = PluginListAdapter(
            onToggle = ::togglePlugin,
            onOpenSettings = ::openPluginSettings
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        reload()
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚装完插件回来，重新扫描一次
        reload()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun reload() {
        scope.launch {
            val plugins = withContext(Dispatchers.IO) {
                runCatching { PluginManager.installedPlugins() }.getOrElse { emptyList() }
            }
            adapter.submit(plugins)
            emptyView.visibility = if (plugins.isEmpty()) View.VISIBLE else View.GONE
            recycler.visibility = if (plugins.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun togglePlugin(meta: PluginMetaInfo, enabled: Boolean) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (enabled) {
                    PluginManager.enable(meta.packageName)
                } else {
                    runCatching { PluginManager.disable(meta.packageName) }
                }
            }
            result.onFailure { error ->
                Toast.makeText(
                    this@PluginListActivity,
                    getString(R.string.plugin_enable_failed, error.message ?: error.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
            reload()
        }
    }

    private fun openPluginSettings(meta: PluginMetaInfo) {
        // 配置界面统一由宿主渲染，插件不需要自带 Activity。
        startActivity(PluginConfigActivity.intent(this, meta.packageName))
    }
}
