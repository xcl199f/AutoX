package org.autojs.autojs.plugin

import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import org.autojs.plugin.api.AutoJsPlugin
import org.autojs.plugin.api.PluginForegroundHost
import org.autojs.plugin.api.PluginHost
import org.autojs.plugin.api.PluginRegistry
import org.autojs.autoxjs.BuildConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 已加载的插件实例及其宿主上下文。
 */
class LoadedPlugin(
    val meta: PluginMetaInfo,
    val plugin: AutoJsPlugin,
    val host: PluginHost,
    val packageContext: Context
)

/**
 * 插件生命周期管理。
 *
 * 加载方式与 AutoJs6 一致：`createPackageContext` 取得插件 APK 的 Context，
 * 其 ClassLoader 以宿主 ClassLoader 为父，因此插件代码可以直接引用宿主类，
 * 而插件自身的 dex 不会被打包进宿主 APK。
 */
object PluginManager {

    private const val TAG = "PluginManager"

    private val loaded = ConcurrentHashMap<String, LoadedPlugin>()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun requireContext(): Context =
        appContext ?: error("PluginManager.init() 尚未调用")

    fun installedPlugins(): List<PluginMetaInfo> {
        val context = requireContext()
        return PluginRepository(context).scan(PluginEnableStore(context))
    }

    fun isLoaded(packageName: String): Boolean = loaded.containsKey(packageName)

    fun loadedPlugin(packageName: String): LoadedPlugin? = loaded[packageName]

    /**
     * 启用插件：加载并调用 onCreate / onEnable。
     */
    @Synchronized
    fun enable(packageName: String): Result<Unit> {
        if (loaded.containsKey(packageName)) {
            return Result.success(Unit)
        }
        val context = requireContext()
        val meta = PluginRepository(context).scan(PluginEnableStore(context))
            .firstOrNull { it.packageName == packageName }
            ?: return Result.failure(IllegalStateException("插件未安装或不是有效的 AutoX 插件"))

        return runCatching {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            val apkPath = appInfo.sourceDir
                ?: throw IllegalStateException("无法定位插件 APK 路径")

            // 插件 Context 只用于访问插件资源与私有偏好；代码加载走 DexClassLoader，
            // 这样不要求插件与宿主同签名（CONTEXT_INCLUDE_CODE 会有该限制）。
            val packageContext = context.createPackageContext(
                packageName,
                Context.CONTEXT_IGNORE_SECURITY
            )
            val dexOut = File(context.codeCacheDir, "plugin-dex/$packageName").apply { mkdirs() }
            val classLoader = DexClassLoader(
                apkPath,
                dexOut.absolutePath,
                appInfo.nativeLibraryDir,
                context.classLoader
            )
            val registryClass = Class.forName(meta.registryClass, true, classLoader)
            val registry = registryClass.getDeclaredConstructor().newInstance() as PluginRegistry
            val plugin = registry.create()

            val host = HostPluginHost(
                hostContext = context,
                pluginContext = packageContext,
                packageName = packageName,
                hostVersionName = BuildConfig.VERSION_NAME,
                hostVersionCode = BuildConfig.VERSION_CODE.toLong(),
                foregroundAllowed = plugin.wantsForegroundService()
            )

            plugin.onCreate(host)
            plugin.onEnable(host)
            loaded[packageName] = LoadedPlugin(meta, plugin, host, packageContext)
            PluginEnableStore(context).setEnabled(packageName, true)
            Log.i(TAG, "plugin enabled: $packageName")
        }.onFailure {
            Log.e(TAG, "failed to enable $packageName", it)
        }
    }

    /**
     * 停用插件：调用 onDisable / onDestroy 并释放 ClassLoader 引用。
     */
    @Synchronized
    fun disable(packageName: String) {
        val context = requireContext()
        PluginEnableStore(context).setEnabled(packageName, false)
        val entry = loaded.remove(packageName) ?: return
        runCatching { entry.plugin.onDisable() }
            .onFailure { Log.w(TAG, "onDisable failed for $packageName", it) }
        runCatching { entry.plugin.onDestroy() }
            .onFailure { Log.w(TAG, "onDestroy failed for $packageName", it) }
        if (PluginHostService.activePlugin() == packageName) {
            PluginHostService.stopForeground(context)
        }
        Log.i(TAG, "plugin disabled: $packageName")
    }

    /**
     * 宿主启动时恢复上次启用的插件。
     */
    fun restoreEnabledPlugins() {
        val context = appContext ?: return
        val store = PluginEnableStore(context)
        val installed = runCatching { installedPlugins() }.getOrElse {
            Log.w(TAG, "scan plugins failed", it)
            return
        }
        installed.filter { store.isEnabled(it.packageName) }.forEach { meta ->
            enable(meta.packageName)
        }
        // 清理已卸载插件残留的启用记录
        val installedPackages = installed.map { it.packageName }.toSet()
        store.enabledPackages().filterNot { it in installedPackages }.forEach {
            store.setEnabled(it, false)
        }
    }
}

/**
 * 宿主侧 [PluginHost] 实现。
 */
internal class HostPluginHost(
    override val hostContext: Context,
    override val pluginContext: Context,
    override val packageName: String,
    override val hostVersionName: String,
    override val hostVersionCode: Long,
    private val foregroundAllowed: Boolean
) : PluginHost {

    override val foreground: PluginForegroundHost = ForegroundProxy(
        context = hostContext,
        pluginId = packageName,
        allowed = foregroundAllowed
    )

    override fun log(message: String) {
        Log.i("AutoXPlugin/$packageName", message)
    }
}

/**
 * 把插件的通知请求转发给宿主的前台服务。
 */
internal class ForegroundProxy(
    private val context: Context,
    private val pluginId: String,
    private val allowed: Boolean
) : PluginForegroundHost {

    override fun startForeground(title: CharSequence, text: CharSequence) {
        if (!allowed) {
            Log.w("ForegroundProxy", "$pluginId 未声明需要前台服务，忽略请求")
            return
        }
        PluginHostService.startForeground(context, pluginId, title, text)
    }

    override fun updateForeground(title: CharSequence, text: CharSequence) {
        startForeground(title, text)
    }

    override fun stopForeground() {
        if (PluginHostService.activePlugin() == pluginId) {
            PluginHostService.stopForeground(context)
        }
    }
}
