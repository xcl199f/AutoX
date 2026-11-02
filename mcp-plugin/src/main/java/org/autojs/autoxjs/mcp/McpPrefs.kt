package org.autojs.autoxjs.mcp

import org.autojs.plugin.api.PluginConfigHost

/**
 * 把宿主侧的插件配置翻译成 [McpConfig]。
 *
 * 配置由宿主持久化（按插件包名分区），插件只负责读取，
 * 因此这里不再持有任何 SharedPreferences 实例。
 */
object McpPrefs {
    internal const val DEFAULT_HOST = "0.0.0.0"
    internal const val DEFAULT_PORT = 27190
    private const val LOCAL_HOST = "127.0.0.1"
    private val LOCAL_HOSTS = setOf(LOCAL_HOST, "localhost", "::1")

    fun load(config: PluginConfigHost): McpConfig {
        // 是否启用完全由宿主的插件开关决定：插件被启用才会走到这里。
        // 不再单独读取 KEY_ENABLED，避免出现两个互相打架的开关。
        val enabled = true
        val allowBase64 = config.getBoolean(McpPrefKeys.KEY_ALLOW_BASE64, false)
        val host = config.getString(McpPrefKeys.KEY_HOST, DEFAULT_HOST)
            .orEmpty()
            .ifBlank { DEFAULT_HOST }
        val allowNetworkPref = config.getBoolean(McpPrefKeys.KEY_ALLOW_NETWORK, true)
        val allowNetwork = allowNetworkPref || !LOCAL_HOSTS.contains(host)
        val port = config.getInt(McpPrefKeys.KEY_PORT, DEFAULT_PORT).coerceIn(1, 65535)
        val token = config.getString(McpPrefKeys.KEY_TOKEN, null)?.trim().orEmpty().ifBlank { null }
        val finalHost = if (allowNetwork) host else LOCAL_HOST
        val idleShutdown = config.getBoolean(McpPrefKeys.KEY_IDLE_SHUTDOWN, true)
        val idleMinutes = config.getInt(McpPrefKeys.KEY_IDLE_MINUTES, McpConfig.DEFAULT_IDLE_MINUTES)
            .coerceIn(McpConfig.MIN_IDLE_MINUTES, McpConfig.MAX_IDLE_MINUTES)

        return McpConfig(
            enabled = enabled,
            host = finalHost,
            port = port,
            token = token,
            allowBase64 = allowBase64,
            allowNetwork = allowNetwork,
            idleShutdown = idleShutdown,
            idleMinutes = idleMinutes
        )
    }
}
