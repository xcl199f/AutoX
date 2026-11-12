package org.autojs.autoxjs.mcp

import android.content.Context
import org.autojs.plugin.api.PluginConfigItem
import org.autojs.plugin.api.PluginConfigSection
import org.autojs.plugin.api.PluginConfigSpec
import org.autojs.plugin.api.PluginConfigType

/**
 * MCP 插件的配置声明。
 *
 * 插件不再自带设置界面，宿主读取本描述后用统一的偏好控件渲染，
 * 值存放在宿主侧，由 [org.autojs.plugin.api.PluginConfigHost] 读写。
 */
internal fun buildMcpConfigSpec(context: Context): PluginConfigSpec = PluginConfigSpec(
    listOf(
        PluginConfigSection(
            title = context.getString(R.string.mcp_settings_category_server),
            items = listOf(
                PluginConfigItem(
                    key = McpPrefKeys.KEY_HOST,
                    type = PluginConfigType.TEXT,
                    title = context.getString(R.string.mcp_settings_host_title),
                    summary = context.getString(R.string.mcp_settings_host_summary),
                    defaultValue = McpPrefs.DEFAULT_HOST,
                    placeholder = "0.0.0.0"
                ),
                PluginConfigItem(
                    key = McpPrefKeys.KEY_PORT,
                    type = PluginConfigType.NUMBER,
                    title = context.getString(R.string.mcp_settings_port_title),
                    defaultValue = McpPrefs.DEFAULT_PORT.toString(),
                    min = 1,
                    max = 65535,
                    placeholder = McpPrefs.DEFAULT_PORT.toString()
                ),
                PluginConfigItem(
                    key = McpPrefKeys.KEY_IDLE_SHUTDOWN,
                    type = PluginConfigType.BOOLEAN,
                    title = context.getString(R.string.mcp_settings_idle_shutdown_title),
                    summary = context.getString(R.string.mcp_settings_idle_shutdown_summary),
                    defaultValue = "true"
                ),
                PluginConfigItem(
                    key = McpPrefKeys.KEY_IDLE_MINUTES,
                    type = PluginConfigType.NUMBER,
                    title = context.getString(R.string.mcp_settings_idle_minutes_title),
                    summary = context.getString(R.string.mcp_settings_idle_minutes_summary),
                    defaultValue = McpConfig.DEFAULT_IDLE_MINUTES.toString(),
                    min = McpConfig.MIN_IDLE_MINUTES,
                    max = McpConfig.MAX_IDLE_MINUTES,
                    placeholder = McpConfig.DEFAULT_IDLE_MINUTES.toString()
                )
            )
        ),
        PluginConfigSection(
            title = context.getString(R.string.mcp_settings_category_security),
            items = listOf(
                PluginConfigItem(
                    key = McpPrefKeys.KEY_TOKEN,
                    type = PluginConfigType.SECRET,
                    title = context.getString(R.string.mcp_settings_token_title),
                    summary = context.getString(R.string.mcp_settings_token_summary),
                    placeholder = context.getString(R.string.mcp_settings_token_placeholder)
                ),
                PluginConfigItem(
                    key = McpPrefKeys.KEY_ALLOW_BASE64,
                    type = PluginConfigType.BOOLEAN,
                    title = context.getString(R.string.mcp_settings_allow_base64_title),
                    summary = context.getString(R.string.mcp_settings_allow_base64_summary),
                    defaultValue = "false"
                ),
                PluginConfigItem(
                    key = McpPrefKeys.KEY_ALLOW_NETWORK,
                    type = PluginConfigType.BOOLEAN,
                    title = context.getString(R.string.mcp_settings_allow_network_title),
                    summary = context.getString(R.string.mcp_settings_allow_network_summary),
                    defaultValue = "false"
                )
            )
        )
    )
)
