package org.autojs.plugin.api

import android.content.Context

/**
 * 宿主提供给插件的能力入口。
 *
 * 注意两个 Context 的区别：
 * - [hostContext] 是宿主的 Application Context，访问脚本目录、无障碍服务、
 *   存储权限等宿主能力时应使用它；
 * - [pluginContext] 是插件自身的 Context，插件私有偏好、资源、字符串应使用它。
 */
interface PluginHost {

    /** 宿主 Application Context，用于访问宿主能力。 */
    val hostContext: Context

    /** 插件自身 Context，用于访问插件私有资源与偏好。 */
    val pluginContext: Context

    /** 插件包名。 */
    val packageName: String

    /** 宿主版本名，便于插件做兼容判断。 */
    val hostVersionName: String

    /** 宿主版本号。 */
    val hostVersionCode: Long

    /** 前台服务代理，见 [AutoJsPlugin.wantsForegroundService]。 */
    val foreground: PluginForegroundHost

    /**
     * 当前进程内是否存在可用的 AutoJs 运行时（无障碍服务、脚本引擎、屏幕截图等）。
     *
     * 宿主有两个进程：主进程负责界面与插件管理，`:script` 进程负责脚本与自动化，
     * [com.stardust.autojs.AutoJs.instance] 只在后者初始化。因此同一个插件会被
     * 加载两次，插件应据此判断自己该在哪个进程里真正干活——需要自动化的常驻服务
     * （如 MCP 的 HTTP 服务）必须在 [hasAutoJsRuntime] 为 true 的进程内启动，
     * 否则会拿到 "AutoJs is not initialized"。
     */
    val hasAutoJsRuntime: Boolean

    /**
     * 插件配置读写入口。配置由宿主持久化并渲染界面，
     * 插件只需在 [AutoJsPlugin.configSpec] 中声明配置项。
     */
    val config: PluginConfigHost

    /** 写入宿主日志（统一带插件前缀，便于排查）。 */
    fun log(message: String)

    /**
     * 重启插件运行时进程（`:script`）。
     *
     * 用途：插件 APK 更新后，宿主进程里仍缓存着旧 ClassLoader 加载的类，
     * 必须让进程重新加载才会用上新代码。
     *
     * 与「热重载实例」的区别：这里是**真正结束进程**，因此能彻底释放
     * 插件持有的资源（HTTP 监听、线程、native 句柄等），不会出现端口被
     * 旧实例占用导致新实例起不来的问题。
     *
     * 代价：本进程内正在运行的脚本会中断，无障碍服务需要重新连接；
     * 宿主会通过 `START_STICKY` 自动重建服务并恢复插件，通常需要数秒。
     *
     * 调用方应先把响应发出去——实现方会延迟一小段时间再结束进程。
     *
     * 注意：声明为抽象方法而非带默认实现，因为 Kotlin 的接口默认方法在
     * 默认编译参数下会生成 `DefaultImpls` 静态调用，子类 override 不会被调用。
     */
    fun restartRuntime()
}
