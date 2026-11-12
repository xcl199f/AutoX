package org.autojs.autojs.plugin

import android.content.Context
import android.content.SharedPreferences
import org.autojs.plugin.api.PluginConfigHost
import org.autojs.plugin.api.PluginConfigListener
import java.util.concurrent.ConcurrentHashMap

/**
 * 插件配置的宿主持久化实现。
 *
 * 每个插件一个独立偏好文件，避免不同插件的 key 互相污染，卸载插件后
 * 只需删除对应文件即可清理。
 *
 * 由于配置的写入方（宿主设置页）与消费方（插件代码）都运行在宿主进程内，
 * [SharedPreferences] 的进程内缓存天然一致，监听器可以同步回调——
 * 这正是把界面收到宿主侧的主要收益。
 */
class PluginConfigStore(
    context: Context,
    val packageName: String,
    /**
     * 配置写入后的回调。主进程写配置时用它通知 `:script` 进程重新应用，
     * 因为 [SharedPreferences] 的进程内缓存不会跨进程同步。
     */
    private val onChange: ((String) -> Unit)? = null
) : PluginConfigHost {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(prefsName(packageName), Context.MODE_PRIVATE)

    /**
     * SharedPreferences 内部用弱引用持有监听器，这里保持强引用，
     * 否则匿名监听器随时可能被回收导致插件收不到变更。
     */
    private val listeners =
        ConcurrentHashMap<PluginConfigListener, SharedPreferences.OnSharedPreferenceChangeListener>()

    // 同一个 key 曾以另一种类型写入（插件升级改了配置 schema）时，
    // SharedPreferences 的强类型 getter 会直接抛 ClassCastException，
    // 导致设置页/插件崩溃。这里退回到默认值，让新 schema 正常生效。
    override fun getString(key: String, defaultValue: String?): String? = try {
        prefs.getString(key, defaultValue)
    } catch (_: ClassCastException) {
        defaultValue
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = try {
        prefs.getBoolean(key, defaultValue)
    } catch (_: ClassCastException) {
        defaultValue
    }

    override fun getInt(key: String, defaultValue: Int): Int = try {
        prefs.getInt(key, defaultValue)
    } catch (_: ClassCastException) {
        defaultValue
    }

    override fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
        onChange?.invoke(key)
    }

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        onChange?.invoke(key)
    }

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
        onChange?.invoke(key)
    }

    override fun registerListener(listener: PluginConfigListener) {
        if (listeners.containsKey(listener)) {
            return
        }
        val bridge = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null) {
                listener.onConfigChanged(key)
            }
        }
        listeners[listener] = bridge
        prefs.registerOnSharedPreferenceChangeListener(bridge)
    }

    override fun unregisterListener(listener: PluginConfigListener) {
        val bridge = listeners.remove(listener) ?: return
        prefs.unregisterOnSharedPreferenceChangeListener(bridge)
    }

    /** 是否存在任何已保存的配置值，供迁移与诊断使用。 */
    fun isEmpty(): Boolean = prefs.all.isEmpty()

    /** 当前全部配置的只读快照，用于跨进程下发给 `:script` 进程。 */
    fun snapshot(): Map<String, Any?> = prefs.all.toMap()

    /** 清除本插件的全部配置，用于"恢复默认值"。 */
    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFIX = "autox_plugin_cfg_"

        fun prefsName(packageName: String): String = PREFIX + packageName
    }
}
