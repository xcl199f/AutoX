package org.autojs.autoxjs.mcp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.autojs.plugin.api.PluginForegroundHost

/**
 * 插件侧的 MCP 服务控制器。
 *
 * 插件 APK 的 Service 组件无法在宿主进程中实例化，因此原来由
 * `McpServerService` 承担的职责拆成两部分：
 * - 前台状态与通知由宿主代理服务维护（[foreground]）；
 * - 服务启停与配置监听留在插件内，由宿主在插件启用时驱动。
 */
internal class McpPluginController(
    private val hostContext: Context,
    private val pluginContext: Context,
    private val foreground: PluginForegroundHost
) : SharedPreferences.OnSharedPreferenceChangeListener {

    private val prefs = McpPrefs.sharedPreferences(pluginContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mcpService: McpService? = null
    private var applyJob: Job? = null

    @Volatile
    private var destroyed = false

    fun start() {
        if (destroyed) {
            return
        }
        prefs.registerOnSharedPreferenceChangeListener(this)
        mcpService = McpService(hostContext)
        applyConfig()
    }

    fun stop() {
        destroyed = true
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(this) }
        applyJob?.cancel()
        runCatching { mcpService?.stop() }
        mcpService = null
        scope.cancel()
        runCatching { foreground.stopForeground() }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key in CONFIG_KEYS) {
            applyConfig()
        }
    }

    private fun applyConfig() {
        if (destroyed) {
            return
        }
        val service = mcpService ?: return
        val config = McpPrefs.load(pluginContext)
        if (config.enabled) {
            foreground.startForeground(
                pluginContext.getString(R.string.mcp_notification_title),
                pluginContext.getString(R.string.mcp_notification_text)
            )
            applyJob?.cancel()
            applyJob = scope.launch {
                runCatching { service.start(config) }
                    .onFailure { Log.w(TAG, "start mcp server failed", it) }
            }
        } else {
            applyJob?.cancel()
            applyJob = scope.launch {
                runCatching { service.stop() }
                    .onFailure { Log.w(TAG, "stop mcp server failed", it) }
            }
            runCatching { foreground.stopForeground() }
        }
    }

    private companion object {
        const val TAG = "McpPluginController"

        val CONFIG_KEYS = setOf(
            McpPrefKeys.KEY_ENABLED,
            McpPrefKeys.KEY_HOST,
            McpPrefKeys.KEY_PORT,
            McpPrefKeys.KEY_TOKEN,
            McpPrefKeys.KEY_ALLOW_BASE64,
            McpPrefKeys.KEY_ALLOW_NETWORK
        )
    }
}
