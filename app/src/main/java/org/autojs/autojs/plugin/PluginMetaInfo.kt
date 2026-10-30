package org.autojs.autojs.plugin

import android.content.ComponentName

/**
 * 已安装插件的静态信息，来自 PackageManager 与插件 APK 的 meta-data。
 */
data class PluginMetaInfo(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val description: String?,
    val registryClass: String,
    val settingsComponent: ComponentName?,
    val enabled: Boolean
)

internal object PluginContract {

    /**
     * 插件 APK 在 `<application>` 内声明的入口类，宿主据此反射实例化 [org.autojs.plugin.api.PluginRegistry]。
     */
    const val META_REGISTRY = "org.autojs.plugin.sdk.registry"

    /**
     * 插件可选的描述信息。
     */
    const val META_DESCRIPTION = "org.autojs.plugin.sdk.description"

    /**
     * 插件必须在其某个组件上声明的 intent action，宿主用它枚举已安装插件。
     * Android 11 之后无法直接枚举全部应用，必须通过 queries 匹配。
     */
    const val ACTION_PLUGIN = "org.autojs.plugin.sdk.PLUGIN"
}
