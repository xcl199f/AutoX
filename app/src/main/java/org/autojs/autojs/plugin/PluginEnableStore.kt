package org.autojs.autojs.plugin

import android.content.Context

/**
 * 记录哪些插件被用户启用。启用状态与插件 APK 本身分离，
 * 卸载插件后残留的记录会在下次扫描时被忽略。
 */
class PluginEnableStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun enabledPackages(): Set<String> =
        prefs.getStringSet(KEY_ENABLED, emptySet())?.toSet() ?: emptySet()

    fun isEnabled(packageName: String): Boolean = enabledPackages().contains(packageName)

    fun setEnabled(packageName: String, enabled: Boolean) {
        val current = enabledPackages().toMutableSet()
        val changed = if (enabled) current.add(packageName) else current.remove(packageName)
        if (changed) {
            prefs.edit().putStringSet(KEY_ENABLED, current).apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "autox_plugins"
        const val KEY_ENABLED = "enabled_packages"
    }
}
