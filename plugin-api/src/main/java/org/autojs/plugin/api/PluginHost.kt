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

    /** 写入宿主日志（统一带插件前缀，便于排查）。 */
    fun log(message: String)
}
