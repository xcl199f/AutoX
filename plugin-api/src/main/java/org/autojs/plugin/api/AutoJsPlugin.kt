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
     * 声明本插件需要用户配置的项目。
     *
     * 宿主据此渲染设置界面，插件无需自带任何 Activity 或布局。
     * 返回 [PluginConfigSpec.EMPTY] 表示该插件没有可配置项。
     * 调用时宿主已执行过 [onCreate]，实现里可以安全使用 [onCreate] 保存的上下文。
     */
    fun configSpec(): PluginConfigSpec = PluginConfigSpec.EMPTY

    /**
     * 宿主界面回到前台时调用。
     *
     * 用于让「因空闲而主动休眠」的常驻插件有机会重新启动。典型场景：MCP 服务
     * 长时间没有请求后停掉了 HTTP 监听，用户再次打开 AutoX 时据此恢复。
     *
     * 只在 [onEnable] 之后、[onDisable] 之前调用；插件若没有休眠逻辑，
     * 保持默认的空实现即可（重复启动应当是幂等的）。
     */
    fun onHostForeground() {}

    /**
     * 是否要求宿主维持一个前台服务，使插件能在后台常驻。
     *
     * 返回 true 时宿主会注册并拉起代理前台服务，插件可通过
     * [PluginHost.foreground] 控制通知内容；返回 false 时
     * [PluginHost.foreground] 的调用会被忽略。
     */
    fun wantsForegroundService(): Boolean = false
}
