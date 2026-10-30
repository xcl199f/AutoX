package org.autojs.autojs.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * 通过 intent action 枚举已安装的插件 APK。
 *
 * Android 11 起无法直接遍历全部已安装应用，这里沿用 AutoJs6 的约定：
 * 插件 APK 需要至少一个组件声明 [PluginContract.ACTION_PLUGIN]，
 * 宿主在 manifest 的 `<queries>` 中声明同一 action 即可枚举到。
 */
class PluginRepository(private val context: Context) {

    private val packageManager: PackageManager
        get() = context.packageManager

    fun scan(enableStore: PluginEnableStore): List<PluginMetaInfo> {
        val intent = Intent(PluginContract.ACTION_PLUGIN)
        val packages = linkedSetOf<String>()

        runCatching {
            packageManager.queryIntentActivities(intent, PackageManager.GET_META_DATA)
        }.getOrElse {
            Log.w(TAG, "queryIntentActivities failed", it)
            emptyList()
        }.forEach { info ->
            info.activityInfo?.packageName?.let(packages::add)
        }

        runCatching {
            packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)
        }.getOrElse {
            Log.w(TAG, "queryIntentServices failed", it)
            emptyList()
        }.forEach { info ->
            info.serviceInfo?.packageName?.let(packages::add)
        }

        val enabled = enableStore.enabledPackages()
        return packages
            .mapNotNull { packageName -> readMeta(packageName, enabled) }
            .sortedBy { it.label.lowercase() }
    }

    private fun readMeta(packageName: String, enabled: Set<String>): PluginMetaInfo? {
        val appInfo = runCatching {
            packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        }.getOrNull() ?: return null

        val meta = appInfo.metaData ?: return null
        val registry = meta.getString(PluginContract.META_REGISTRY)?.takeIf { it.isNotBlank() }
            ?: return null

        return PluginMetaInfo(
            packageName = packageName,
            label = appInfo.loadLabel(packageManager).toString(),
            versionName = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            }.getOrDefault(""),
            versionCode = runCatching {
                val info = packageManager.getPackageInfo(packageName, 0)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
            }.getOrDefault(0L),
            description = meta.getString(PluginContract.META_DESCRIPTION),
            registryClass = registry,
            settingsComponent = resolveSettings(packageName),
            enabled = enabled.contains(packageName)
        )
    }

    /**
     * 插件可选地提供一个设置界面：声明了 action 的 Activity 中，
     * 取第一个非入口组件作为设置页。
     */
    private fun resolveSettings(packageName: String): ComponentName? {
        val intent = Intent(PluginContract.ACTION_PLUGIN).setPackage(packageName)
        val list = runCatching {
            packageManager.queryIntentActivities(intent, 0)
        }.getOrElse { emptyList() }
        val activity = list.firstOrNull()?.activityInfo ?: return null
        return ComponentName(packageName, activity.name)
    }

    private companion object {
        const val TAG = "PluginRepository"
    }
}
