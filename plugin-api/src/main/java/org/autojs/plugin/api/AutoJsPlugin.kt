package org.autojs.plugin.api

/**
 * AutoX 插件契约。
 *
 * 插件以独立 APK 的形式安装，宿主在运行时通过 [PluginRegistry] 反射实例化入口类，
 * 插件代码因此运行在宿主进程内，可以直接使用宿主提供的运行时能力。
 *
 * 生命周期顺序：
 * `onCreate` -> `onEnable` -> (`onDisable` -> `onEnable`)* -> `onDestroy`
 */
interface AutoJsPlugin {

    /**
     * 插件被宿主加载后立即调用，用于初始化不依赖启用状态的对象。
     */
    fun onCreate(host: PluginHost)

    /**
     * 插件被用户启用时调用。常驻型插件（如 MCP 服务）应在此处启动后台能力。
     */
    fun onEnable(host: PluginHost) {}

    /**
     * 插件被用户停用时调用，必须释放 [onEnable] 中申请的全部资源。
     */
    fun onDisable() {}

    /**
     * 宿主销毁插件实例时调用。
     */
    fun onDestroy() {}

    /**
     * 是否要求宿主维持一个前台服务，使插件能在后台常驻。
     *
     * 返回 true 时宿主会注册并拉起代理前台服务，插件可通过
     * [PluginHost.foreground] 控制通知内容；返回 false 时
     * [PluginHost.foreground] 的调用会被忽略。
     */
    fun wantsForegroundService(): Boolean = false
}
