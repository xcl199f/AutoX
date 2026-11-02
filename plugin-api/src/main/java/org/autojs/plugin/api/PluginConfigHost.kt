package org.autojs.plugin.api

/**
 * 配置变更回调。宿主在主进程内直接回调，不涉及跨进程通信。
 */
fun interface PluginConfigListener {
    fun onConfigChanged(key: String)
}

/**
 * 插件配置的读写入口。
 *
 * 配置由宿主统一持久化（按插件包名分区），插件自身不持有配置文件；
 * 因此用户在宿主设置页里改动后，插件注册的监听器会立即收到回调。
 *
 * 读取时传入的 `defaultValue` 应与 [PluginConfigItem.defaultValue] 保持一致。
 */
interface PluginConfigHost {

    fun getString(key: String, defaultValue: String?): String?

    fun getBoolean(key: String, defaultValue: Boolean): Boolean

    fun getInt(key: String, defaultValue: Int): Int

    fun putString(key: String, value: String?)

    fun putBoolean(key: String, value: Boolean)

    fun putInt(key: String, value: Int)

    /** 注册变更监听。插件应在 [AutoJsPlugin.onDisable] 中注销。 */
    fun registerListener(listener: PluginConfigListener)

    fun unregisterListener(listener: PluginConfigListener)
}
