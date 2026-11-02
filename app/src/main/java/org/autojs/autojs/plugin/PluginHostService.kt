package org.autojs.autojs.plugin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.autojs.autoxjs.R

/**
 * 宿主为插件提供的前台服务代理。
 *
 * 插件 APK 中的 Service 组件无法在宿主进程里实例化，因此需要常驻的插件
 * （例如 MCP 服务）通过 [org.autojs.plugin.api.PluginForegroundHost] 请求本服务，
 * 由宿主统一维护通知与前台状态。
 */
class PluginHostService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // 进程被回收后系统重启服务，此时没有插件请求，交由插件的 onEnable 重新拉起。
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID)
        if (pluginId.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent.getCharSequenceExtra(EXTRA_TITLE)?.toString()
            ?: getString(R.string.plugin_foreground_default_title)
        val text = intent.getCharSequenceExtra(EXTRA_TEXT)?.toString().orEmpty()

        return try {
            ensureChannel()
            startForeground(NOTIFICATION_ID, buildNotification(title, text))
            activePluginId = pluginId
            START_STICKY
        } catch (e: RuntimeException) {
            Log.w(TAG, "startForeground failed for $pluginId", e)
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        activePluginId = null
        super.onDestroy()
    }

    /**
     * Android 15 (API 35) 起，dataSync / 媒体处理类前台服务在应用处于后台时
     * 有运行时长限制。超限后系统回调本方法，服务必须在数秒内 stopSelf()，
     * 否则抛出 ForegroundServiceDidNotStopInTimeException 直接崩溃应用。
     *
     * PluginHostService 只是插件的常驻代理（且实际运行在主进程，对真正需要
     * 存活的 :script 进程插件没有 keep-alive 价值），因此超时后直接优雅退出。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground service timeout (type=$fgsType), stopping")
        stopForegroundCompat()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.plugin_foreground_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.plugin_foreground_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    companion object {
        private const val TAG = "PluginHostService"
        private const val CHANNEL_ID = "autox_plugin_foreground"
        private const val NOTIFICATION_ID = 27191
        private const val EXTRA_PLUGIN_ID = "plugin_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val ACTION_STOP = "org.autojs.autojs.plugin.action.STOP_FOREGROUND"

        @Volatile
        private var activePluginId: String? = null

        fun startForeground(
            context: Context,
            pluginId: String,
            title: CharSequence,
            text: CharSequence
        ): Boolean {
            val intent = Intent(context, PluginHostService::class.java).apply {
                putExtra(EXTRA_PLUGIN_ID, pluginId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: RuntimeException) {
                // Android 12+ 后台启动前台服务受限，此时静默失败，等下次回到前台再试。
                Log.w(TAG, "startForegroundService rejected for $pluginId", e)
                false
            }
        }

        fun stopForeground(context: Context) {
            val intent = Intent(context, PluginHostService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "stopForeground failed", it) }
        }

        fun activePlugin(): String? = activePluginId
    }
}
