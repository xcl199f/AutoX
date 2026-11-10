package org.autojs.autojs.plugin

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import dalvik.system.DexClassLoader
import org.autojs.plugin.api.AutoJsPlugin
import org.autojs.plugin.api.PluginConfigHost
import org.autojs.plugin.api.PluginConfigSpec
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

    /** 与宿主 plugin-api 契约不兼容的插件，供设置界面给出明确提示。 */
    private val incompatiblePlugins = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var appContext: Context? = null

    /**
     * 当前是否处于 `:script` 进程（插件运行期宿主）。
     *
     * 由 [PluginRuntimeService] 置位。该进程内 `AutoJs.instance` 可用，
     * 插件在这里才能真正操作设备；主进程只负责界面与配置。
     */
    @Volatile
    var isRuntimeProcess: Boolean = false
        internal set

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

    /** 插件的配置存储（按包名分区，与插件是否启用无关）。 */
    fun configStore(packageName: String): PluginConfigStore {
        val context = requireContext()
        return PluginConfigStore(context, packageName) { syncRuntime() }
    }

    /**
     * 该插件是否因跨 APK 契约不匹配而不可用。
     */
    fun isPluginIncompatible(packageName: String): Boolean = packageName in incompatiblePlugins

    /**
     * 插件声明的配置描述。仅在插件已加载时可用，未加载返回 null。
     *
     * 插件以 compileOnly 依赖宿主侧 plugin-api，契约一旦变更（例如给
     * PluginConfigItem 增加带默认值的字段），旧插件调用的 Kotlin 默认参数
     * 合成构造函数签名就会与宿主类不一致，抛 NoSuchMethodError。它属于
     * LinkageError 而非 Exception，普通 catch 抓不到，因此这里统一兜底，
     * 避免直接闪退。
     */
    fun configSpec(packageName: String): PluginConfigSpec? {
        val plugin = loaded[packageName]?.plugin ?: return null
        return try {
            plugin.configSpec().also { incompatiblePlugins.remove(packageName) }
        } catch (e: LinkageError) {
            Log.e(TAG, "插件 $packageName 与宿主的 plugin-api 契约不匹配，请重新安装插件", e)
            incompatiblePlugins.add(packageName)
            null
        }
    }

    /**
     * 加载插件但不启用：只执行 [AutoJsPlugin.onCreate]。
     *
     * 设置界面需要在插件未启用时也能展示配置项，因此加载与启用必须解耦。
     * 已加载的实例会被复用，重复调用不会重复创建 ClassLoader。
     *
     * @param configSnapshot `:script` 进程下由主进程下发的配置快照；
     *   主进程传 null，插件直接读写 SharedPreferences。
     */
    @Synchronized
    fun ensureLoaded(packageName: String, configSnapshot: Bundle? = null): Result<LoadedPlugin> {
        loaded[packageName]?.let { return Result.success(it) }

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

            val configHost: PluginConfigHost = if (isRuntimeProcess) {
                SnapshotConfigHost(configSnapshot ?: Bundle())
            } else {
                PluginConfigStore(context, packageName) { syncRuntime() }
            }

            val host = HostPluginHost(
                hostContext = context,
                pluginContext = packageContext,
                packageName = packageName,
                hostVersionName = BuildConfig.VERSION_NAME,
                hostVersionCode = BuildConfig.VERSION_CODE.toLong(),
                foregroundAllowed = plugin.wantsForegroundService(),
                config = configHost,
                hasAutoJsRuntime = isRuntimeProcess
            )

            plugin.onCreate(host)
            LoadedPlugin(meta, plugin, host, packageContext).also {
                loaded[packageName] = it
            }
        }.onFailure {
            Log.e(TAG, "failed to load $packageName", it)
        }
    }

    /**
     * 启用插件：确保已加载后调用 onEnable。
     */
    @Synchronized
    fun enable(packageName: String): Result<Any> {
        val entry = ensureLoaded(packageName).getOrElse {
            return Result.failure(it)
        }
        return runCatching {
            entry.plugin.onEnable(entry.host)
            PluginEnableStore(requireContext()).setEnabled(packageName, true)
            Log.i(TAG, "plugin enabled: $packageName")
        }.onFailure {
            Log.e(TAG, "failed to enable $packageName", it)
        }.also {
            if (it.isSuccess) {
                syncRuntime()
            }
        }
    }

    /**
     * 停用插件：调用 onDisable 释放运行期资源，但保留已加载的实例，
     * 使设置界面在插件停用状态下仍能读写配置。
     */
    @Synchronized
    fun disable(packageName: String) {
        val context = requireContext()
        PluginEnableStore(context).setEnabled(packageName, false)
        val entry = loaded[packageName] ?: return
        runCatching { entry.plugin.onDisable() }
            .onFailure { Log.w(TAG, "onDisable failed for $packageName", it) }
        runCatching { entry.host.foreground.stopForeground() }
            .onFailure { Log.w(TAG, "stopForeground failed for $packageName", it) }
        Log.i(TAG, "plugin disabled: $packageName")
        syncRuntime()
    }

    /**
     * 彻底卸载插件实例（插件被移除或需要强制重载时使用）。
     */
    @Synchronized
    fun unload(packageName: String) {
        val entry = loaded.remove(packageName) ?: return
        runCatching { entry.plugin.onDisable() }
            .onFailure { Log.w(TAG, "onDisable failed for $packageName", it) }
        runCatching { entry.plugin.onDestroy() }
            .onFailure { Log.w(TAG, "onDestroy failed for $packageName", it) }
        runCatching { entry.host.foreground.stopForeground() }
            .onFailure { Log.w(TAG, "stopForeground failed for $packageName", it) }
        Log.i(TAG, "plugin unloaded: $packageName")
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
        syncRuntime()
    }

    /**
     * 把「当前应运行的插件 + 其配置快照」下发给 `:script` 进程。
     *
     * 只在主进程调用；`:script` 进程收到后走 [applyRuntimePlugins]。
     */
    fun syncRuntime() {
        if (isRuntimeProcess) {
            return
        }
        val context = appContext ?: return
        val store = PluginEnableStore(context)
        val payload = store.enabledPackages().map { pkg ->
            pkg to PluginConfigStore(context, pkg).snapshot()
        }
        PluginRuntimeService.sync(context, payload)
    }

    /**
     * 最近一次下发给 `:script` 进程的插件集合与配置快照。
     *
     * 热重载（[reloadPlugin]）需要复用它来重建单个插件，避免影响其它插件。
     */
    @Volatile
    private var runtimeDesired: Map<String, Bundle> = emptyMap()

    /**
     * 在 `:script` 进程内把插件运行状态对齐到 [desired]。
     *
     * 配置变化时也会重建实例，这样端口之类的改动无需重启宿主即可生效。
     */
    @Synchronized
    fun applyRuntimePlugins(desired: Map<String, Bundle>) {
        runtimeDesired = desired
        loaded.keys.filterNot { it in desired.keys }.forEach { stale ->
            unload(stale)
        }
        desired.forEach { (packageName, snapshot) ->
            rebuild(packageName, snapshot)
        }
        Log.i(TAG, "runtime plugins applied: ${desired.keys}")
    }

    /**
     * 宿主界面回到前台时，通知所有已加载插件（仅 `:script` 进程有效）。
     *
     * 用于唤醒因空闲而主动休眠的常驻插件——MCP 服务停掉监听后没有别的途径
     * 感知「用户回来了」，只能靠宿主主动通知。
     */
    fun applyHostForeground() {
        if (loaded.isEmpty()) {
            Log.d(TAG, "applyHostForeground skipped, no plugin loaded")
            return
        }
        loaded.forEach { (packageName, entry) ->
            runCatching { entry.plugin.onHostForeground() }
                .onFailure { Log.w(TAG, "onHostForeground failed for $packageName", it) }
        }
    }

    /** `:script` 进程里是否已经加载了插件（用于判断前台唤醒前要不要先补一次加载）。 */
    fun hasLoadedPlugins(): Boolean = loaded.isNotEmpty()

    /**
     * 热重载单个插件：销毁旧实例并加载最新的 APK，不杀进程。
     *
     * 只在 `:script` 进程有效——主进程没有运行期插件集合，也不该重建运行期实例。
     */
    @Synchronized
    fun reloadPlugin(packageName: String): Boolean {
        if (!isRuntimeProcess) {
            Log.w(TAG, "reloadPlugin ignored outside runtime process: $packageName")
            return false
        }
        val snapshot = runtimeDesired[packageName] ?: run {
            Log.w(TAG, "reloadPlugin ignored, plugin not running: $packageName")
            return false
        }
        Log.i(TAG, "reloading plugin: $packageName")
        rebuild(packageName, snapshot)
        return true
    }

    /** 销毁旧实例并按 [snapshot] 重新加载启用。 */
    private fun rebuild(packageName: String, snapshot: Bundle) {
        loaded.remove(packageName)?.let { old ->
            runCatching { old.plugin.onDisable() }
                .onFailure { Log.w(TAG, "onDisable failed for $packageName", it) }
            runCatching { old.plugin.onDestroy() }
                .onFailure { Log.w(TAG, "onDestroy failed for $packageName", it) }
        }
        ensureLoaded(packageName, snapshot)
            .onSuccess { entry ->
                runCatching { entry.plugin.onEnable(entry.host) }
                    .onFailure { Log.e(TAG, "failed to enable $packageName in runtime", it) }
            }
            .onFailure { Log.e(TAG, "failed to load $packageName in runtime", it) }
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
    private val foregroundAllowed: Boolean,
    override val config: PluginConfigHost,
    override val hasAutoJsRuntime: Boolean
) : PluginHost {

    override val foreground: PluginForegroundHost = ForegroundProxy(
        context = hostContext,
        pluginId = packageName,
        allowed = foregroundAllowed
    )

    override fun log(message: String) {
        Log.i("AutoXPlugin/$packageName", message)
    }

    /**
     * 延迟结束 `:script` 进程。
     *
     * 必须异步：调用方通常正处在插件自己的请求链路里（例如 MCP 的一次 tools/call），
     * 立刻结束进程会把当前响应打断。延迟到主线程队列后执行，给调用方留出返回时间。
     *
     * 进程结束后由 [PluginRuntimeService] 的 START_STICKY 语义重建，
     * 并在 `onStartCommand(null)` 里按磁盘状态恢复插件。
     */
    override fun restartRuntime() {
        Handler(Looper.getMainLooper()).postDelayed({
            Log.i("AutoXPlugin/$packageName", "restarting runtime process, pid=${android.os.Process.myPid()}")
            android.os.Process.killProcess(android.os.Process.myPid())
        }, RESTART_DELAY_MILLIS)
    }

    private companion object {
        const val RESTART_DELAY_MILLIS = 600L
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

    /**
     * 是否由本代理真正拉起过前台服务。
     *
     * 不能用 [PluginHostService.activePlugin] 判断：那个静态量只在主进程有效，
     * 而插件实际运行在 `:script` 进程，跨进程读不到。
     */
    @Volatile
    private var started = false

    override fun startForeground(title: CharSequence, text: CharSequence) {
        if (!allowed) {
            Log.w("ForegroundProxy", "$pluginId 未声明需要前台服务，忽略请求")
            return
        }
        started = PluginHostService.startForeground(context, pluginId, title, text)
    }

    override fun updateForeground(title: CharSequence, text: CharSequence) {
        startForeground(title, text)
    }

    override fun stopForeground() {
        if (!started) {
            return
        }
        started = false
        PluginHostService.stopForeground(context)
    }
}
