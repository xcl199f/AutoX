package org.autojs.autoxjs.mcp

import org.autojs.plugin.api.AutoJsPlugin
import org.autojs.plugin.api.PluginHost
import org.autojs.plugin.api.PluginRegistry

/**
 * 插件入口：宿主读取 AndroidManifest 中的
 * `org.autojs.plugin.sdk.registry` 元数据后反射实例化本类。
 */
class McpPluginRegistry : PluginRegistry {
    override fun create(): AutoJsPlugin = McpPluginEntry()
}

/**
 * MCP 插件实现。启用时启动 MCP 服务，停用时释放。
 */
class McpPluginEntry : AutoJsPlugin {

    private var controller: McpPluginController? = null

    override fun onCreate(host: PluginHost) {
        host.log("MCP 插件已创建，版本 ${host.hostVersionName}")
    }

    override fun onEnable(host: PluginHost) {
        controller?.stop()
        controller = McpPluginController(
            hostContext = host.hostContext,
            pluginContext = host.pluginContext,
            foreground = host.foreground
        ).also { it.start() }
    }

    override fun onDisable() {
        controller?.stop()
        controller = null
    }

    override fun onDestroy() {
        controller?.stop()
        controller = null
    }

    /**
     * MCP 需要在后台持续监听端口，因此借宿主的代理前台服务保活。
     */
    override fun wantsForegroundService(): Boolean = true
}
