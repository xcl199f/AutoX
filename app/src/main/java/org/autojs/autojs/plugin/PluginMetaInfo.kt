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
     *
     * 该组件只作为枚举标记，不需要也不应该有界面。
     */
    const val ACTION_PLUGIN = "org.autojs.plugin.sdk.PLUGIN"

    /**
     * 插件可选声明的自定义设置页 action。
     *
     * 正常情况下插件不需要自带界面——配置项通过
     * [org.autojs.plugin.api.AutoJsPlugin.configSpec] 声明，由宿主渲染。
     * 仅当配置无法用 schema 表达时，才用它提供一个"高级设置"入口。
     */
    const val ACTION_SETTINGS = "org.autojs.plugin.sdk.SETTINGS"
}
