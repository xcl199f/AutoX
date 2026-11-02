package org.autojs.autoxjs.mcp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.autojs.plugin.api.PluginConfigHost
import org.autojs.plugin.api.PluginConfigListener
import org.autojs.plugin.api.PluginForegroundHost

/**
 * 插件侧的 MCP 服务控制器。
 *
 * 插件 APK 的 Service 组件无法在宿主进程中实例化，因此原来由
 * `McpServerService` 承担的职责拆成两部分：
 * - 前台状态与通知由宿主代理服务维护（[foreground]）；
 * - 服务启停与配置监听留在插件内，由宿主在插件启用时驱动。
 *
 * 配置读写走 [PluginConfigHost]，与宿主的设置界面同进程，
 * 用户改完即时生效，无需重启宿主。
 */
internal class McpPluginController(
    private val hostContext: Context,
    private val pluginContext: Context,
    private val foreground: PluginForegroundHost,
    private val config: PluginConfigHost,
    private val restart: () -> Unit
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mcpService: McpService? = null
    private var applyJob: Job? = null

    @Volatile
    private var destroyed = false

    private val configListener = PluginConfigListener { key ->
        if (key in CONFIG_KEYS) {
            applyConfig()
        }
    }

    fun start() {
        if (destroyed) {
            return
        }
        config.registerListener(configListener)
        mcpService = McpService(hostContext, restart)
        applyConfig()
    }

    fun stop() {
        destroyed = true
        runCatching { config.unregisterListener(configListener) }
        applyJob?.cancel()
        runCatching { mcpService?.stop() }
        mcpService = null
        scope.cancel()
        runCatching { foreground.stopForeground() }
    }

    /**
     * 宿主界面回到前台：若服务因空闲休眠停掉了监听，这里把它重新拉起来。
     *
     * MCP 是纯被动服务，停掉之后没有别的途径能感知「用户回来了」，
     * 只能依赖宿主的这个回调。
     */
    fun onHostForeground() {
        if (destroyed) {
            return
        }
        val service = mcpService ?: return
        if (!service.isIdleSleeping) {
            return
        }
        scope.launch {
            runCatching { service.resumeFromIdle() }
                .onFailure { Log.w(TAG, "resume from idle failed", it) }
        }
    }

    private fun applyConfig() {
        if (destroyed) {
            return
        }
        val service = mcpService ?: return
        val mcpConfig = McpPrefs.load(config)
        if (mcpConfig.enabled) {
            foreground.startForeground(
                pluginContext.getString(R.string.mcp_notification_title),
                pluginContext.getString(R.string.mcp_notification_text)
            )
            applyJob?.cancel()
            applyJob = scope.launch {
                runCatching { service.start(mcpConfig) }
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
            McpPrefKeys.KEY_HOST,
            McpPrefKeys.KEY_PORT,
            McpPrefKeys.KEY_TOKEN,
            McpPrefKeys.KEY_ALLOW_BASE64,
            McpPrefKeys.KEY_ALLOW_NETWORK,
            McpPrefKeys.KEY_IDLE_SHUTDOWN,
            McpPrefKeys.KEY_IDLE_MINUTES
        )
    }
}
