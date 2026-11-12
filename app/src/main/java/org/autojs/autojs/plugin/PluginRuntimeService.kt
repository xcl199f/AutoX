package org.autojs.autojs.plugin

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import org.autojs.plugin.api.PluginConfigHost
import org.autojs.plugin.api.PluginConfigListener

/**
 * 插件运行期宿主，运行在 `:script` 进程。
 *
 * ## 为什么需要这个服务
 *
 * 宿主有两个进程：
 * - 主进程：界面、插件管理、插件设置页；
 * - `:script` 进程：`AutoJs` 运行时、无障碍服务、脚本引擎、屏幕截图。
 *
 * `com.stardust.autojs.AutoJs.instance` 只在 `:script` 进程里由
 * `App.init()` 初始化，因此插件若在主进程里调用它会拿到
 * "AutoJs is not initialized"。MCP 这类需要真正操作设备的常驻插件，
 * 必须在 `:script` 进程内运行，所以由主进程通过本服务把「启用状态 + 配置快照」
 * 下发过来，在 `:script` 进程里重新加载并启用插件。
 *
 * 配置值随 Intent 传递而不是让 `:script` 进程去读 SharedPreferences，
 * 因为 SharedPreferences 的进程内缓存不会跨进程同步，读到的会是旧值。
 */
class PluginRuntimeService : Service() {

    override fun onCreate() {
        super.onCreate()
        PluginManager.init(this)
        PluginManager.isRuntimeProcess = true
        Log.i(TAG, "plugin runtime service created, pid=${android.os.Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // 进程被回收后系统重建服务，此时没有快照可用。
            // 新进程的 SharedPreferences 会重新从磁盘读取，因此直接按磁盘状态恢复。
            runCatching { PluginManager.applyRuntimePlugins(loadDesiredFromDisk()) }
                .onFailure { Log.e(TAG, "restore runtime plugins failed", it) }
            return START_STICKY
        }
        if (intent.action == ACTION_HOST_FOREGROUND) {
            // 若本进程还没加载任何插件（服务被拉起但尚未同步过），先补一次，
            // 否则通知会打到空集合上，休眠中的插件永远醒不过来。
            // 优先用主进程随通知带来的快照：本进程若已存活，其 SharedPreferences
            // 缓存可能是旧值，不能在这里读磁盘。
            runCatching {
                if (!PluginManager.hasLoadedPlugins()) {
                    PluginManager.applyRuntimePlugins(
                        desiredFromIntent(intent) ?: loadDesiredFromDisk()
                    )
                }
                PluginManager.applyHostForeground()
            }.onFailure { Log.e(TAG, "host foreground notify failed", it) }
            return START_STICKY
        }
        val desired = desiredFromIntent(intent) ?: emptyMap()
        runCatching { PluginManager.applyRuntimePlugins(desired) }
            .onFailure { Log.e(TAG, "apply runtime plugins failed", it) }
        return START_STICKY
    }

    /** 从 Intent 里还原「插件 -> 配置快照」；旧版通知没带快照时返回 null。 */
    private fun desiredFromIntent(intent: Intent): Map<String, Bundle>? {
        val packages = intent.getStringArrayListExtra(EXTRA_PACKAGES) ?: return null
        val configs = intent.getParcelableArrayListExtra<Bundle>(EXTRA_CONFIGS).orEmpty()
        return LinkedHashMap<String, Bundle>(packages.size).apply {
            packages.forEachIndexed { index, pkg ->
                this[pkg] = configs.getOrNull(index) ?: Bundle()
            }
        }
    }

    private fun loadDesiredFromDisk(): Map<String, Bundle> {
        val store = PluginEnableStore(this)
        return store.enabledPackages().associateWith { pkg ->
            toConfigBundle(PluginConfigStore(this, pkg).snapshot())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "PluginRuntimeService"
        private const val EXTRA_PACKAGES = "packages"
        private const val EXTRA_CONFIGS = "configs"

        /** 宿主界面回到前台的唤醒通知，同时携带主进程里最新的启用状态与配置快照。 */
        const val ACTION_HOST_FOREGROUND = "org.autojs.autojs.plugin.action.HOST_FOREGROUND"

        private const val FOREGROUND_NOTIFY_THROTTLE_MILLIS = 1_000L

        /** 上一次发送前台唤醒通知的时间，用于节流。 */
        @Volatile
        private var lastForegroundNotifyAt = 0L

        /**
         * 通知 `:script` 进程「宿主界面回到前台了」。
         *
         * 插件若因空闲休眠停掉了常驻能力（如 MCP 的 HTTP 监听），可借此恢复。
         * 调用方应保证自身处于前台——后台调用 [Context.startService] 在
         * Android 8+ 会被系统拒绝。
         */
        fun notifyHostForeground(context: Context) {
            // 界面间切换会连续触发，1 秒内只发一次即可。
            val now = System.currentTimeMillis()
            if (now - lastForegroundNotifyAt < FOREGROUND_NOTIFY_THROTTLE_MILLIS) {
                return
            }
            // 一个插件都没启用时不必为唤醒而拉起 :script 进程——那会白白多一个常驻进程。
            // 同时带上主进程里最新的配置快照，避免 :script 进程用自己那份过期 SP 缓存。
            val packages = runCatching {
                PluginEnableStore(context).enabledPackages().map { pkg ->
                    pkg to PluginConfigStore(context, pkg).snapshot()
                }
            }.getOrDefault(emptyList<Pair<String, Map<String, Any?>>>())
            if (packages.isEmpty()) {
                return
            }
            lastForegroundNotifyAt = now
            val intent = buildSyncIntent(context, packages).apply {
                action = ACTION_HOST_FOREGROUND
            }
            try {
                context.startService(intent)
            } catch (e: RuntimeException) {
                Log.w(TAG, "failed to notify host foreground", e)
            }
        }

        /**
         * 把「当前应当运行的插件集合」下发给 `:script` 进程。
         *
         * 配置快照变化的插件会被重建，因此改端口等配置无需重启宿主即可生效。
         */
        fun sync(context: Context, packages: List<Pair<String, Map<String, Any?>>>) {
            try {
                context.startService(buildSyncIntent(context, packages))
            } catch (e: RuntimeException) {
                Log.w(TAG, "failed to start plugin runtime service", e)
            }
        }

        private fun buildSyncIntent(
            context: Context,
            packages: List<Pair<String, Map<String, Any?>>>
        ): Intent = Intent(context, PluginRuntimeService::class.java).also { intent ->
            intent.putStringArrayListExtra(EXTRA_PACKAGES, ArrayList(packages.map { it.first }))
            val configs = ArrayList<Bundle>(packages.size)
            packages.forEach { configs.add(toConfigBundle(it.second)) }
            intent.putParcelableArrayListExtra(EXTRA_CONFIGS, configs)
        }
    }
}

/** 把配置快照打包成可跨进程传递的 [Bundle]。 */
internal fun toConfigBundle(values: Map<String, Any?>): Bundle {
    val bundle = Bundle()
    values.forEach { (key, value) ->
        when (value) {
            is String -> bundle.putString(key, value)
            is Int -> bundle.putInt(key, value)
            is Long -> bundle.putLong(key, value)
            is Float -> bundle.putFloat(key, value)
            is Boolean -> bundle.putBoolean(key, value)
            is Set<*> -> bundle.putStringArrayList(
                key, ArrayList(value.filterIsInstance<String>())
            )
            // 其它类型宿主侧也用不到，直接忽略
            else -> Unit
        }
    }
    return bundle
}

/**
 * `:script` 进程内插件看到的配置。
 *
 * 数据来自主进程下发的快照，只读：写入方永远是主进程的设置界面。
 * 配置变化时宿主会重建插件实例，因此这里不需要跨进程的变更通知。
 */
internal class SnapshotConfigHost(private val values: Bundle) : PluginConfigHost {

    override fun getString(key: String, defaultValue: String?): String? =
        if (values.containsKey(key)) values.getString(key) else defaultValue

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        if (values.containsKey(key)) values.getBoolean(key, defaultValue) else defaultValue

    override fun getInt(key: String, defaultValue: Int): Int =
        if (values.containsKey(key)) values.getInt(key, defaultValue) else defaultValue

    override fun putString(key: String, value: String?) {
        Log.w(TAG, "配置写入应发生在主进程，运行期进程忽略 key=$key")
    }

    override fun putBoolean(key: String, value: Boolean) {
        Log.w(TAG, "配置写入应发生在主进程，运行期进程忽略 key=$key")
    }

    override fun putInt(key: String, value: Int) {
        Log.w(TAG, "配置写入应发生在主进程，运行期进程忽略 key=$key")
    }

    override fun registerListener(listener: PluginConfigListener) {
        // 配置变更由宿主重建插件实例来体现，此处无需监听
    }

    override fun unregisterListener(listener: PluginConfigListener) {
        // 同上
    }

    private companion object {
        const val TAG = "SnapshotConfigHost"
    }
}
