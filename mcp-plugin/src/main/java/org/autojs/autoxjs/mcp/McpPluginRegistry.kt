package org.autojs.autoxjs.mcp

import org.autojs.plugin.api.AutoJsPlugin
import org.autojs.plugin.api.PluginConfigSpec
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
 *
 * 插件不自带界面，配置项通过 [configSpec] 声明，由宿主渲染。
 */
class McpPluginEntry : AutoJsPlugin {

    private var host: PluginHost? = null
    private var controller: McpPluginController? = null

    override fun onCreate(host: PluginHost) {
        this.host = host
        host.log("MCP 插件已创建，宿主版本 ${host.hostVersionName}")
    }

    override fun onEnable(host: PluginHost) {
        controller?.stop()
        controller = null
        if (!host.hasAutoJsRuntime) {
            // 主进程只负责界面与配置。MCP 的截图/点击/查找节点都依赖
            // AutoJs 运行时（无障碍服务），它在 :script 进程里，因此
            // 服务实体必须由那边的实例拉起，否则会拿到
            // "AutoJs is not initialized"。
            host.log("主进程：MCP 服务交由 :script 进程托管")
            return
        }
        controller = McpPluginController(
            hostContext = host.hostContext,
            pluginContext = host.pluginContext,
            foreground = host.foreground,
            config = host.config,
            restart = { host.restartRuntime() }
        ).also { it.start() }
    }

    override fun onDisable() {
        controller?.stop()
        controller = null
    }

    /**
     * 宿主界面回到前台。只有 `:script` 进程里的实例持有 controller，
     * 主进程实例是空操作。
     */
    override fun onHostForeground() {
        controller?.onHostForeground()
    }

    override fun onDestroy() {
        controller?.stop()
        controller = null
    }

    /**
     * 宿主渲染设置页所需的配置描述。
     * 此时 [onCreate] 已执行，可以安全使用插件上下文取字符串资源。
     */
    override fun configSpec(): PluginConfigSpec {
        val context = host?.pluginContext ?: return PluginConfigSpec.EMPTY
        return buildMcpConfigSpec(context)
    }

    /**
     * MCP 不借宿主代理前台服务保活。原因：
     * 1. PluginHostService 运行在**主进程**，而 MCP 的 HTTP 服务跑在 **:script 进程**，
     *    主进程的前台服务无法提升 :script 进程的优先级，保活无效；
     * 2. 该服务声明为 dataSync 类型，Android 15 起此类前台服务在后台有时长限制，
     *    超时系统会回调 onTimeout，若不 stopSelf 便崩溃（见 PluginHostService.onTimeout）。
     * 因此让 MCP 以普通后台形态运行，由 :script 进程自身的运行时维持存活。
     */
    override fun wantsForegroundService(): Boolean = false
}
