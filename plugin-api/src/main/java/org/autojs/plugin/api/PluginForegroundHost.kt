package org.autojs.plugin.api

/**
 * 前台服务代理。
 *
 * 插件 APK 内声明的 Service 组件无法在宿主进程中实例化，因此需要常驻的插件
 * 必须通过本接口借宿主的代理服务进入前台。宿主会负责创建通知渠道并维护
 * 前台状态，插件只负责提供文案。
 *
 * 仅当 [AutoJsPlugin.wantsForegroundService] 返回 true 时调用才有效。
 */
interface PluginForegroundHost {

    /**
     * 启动（或更新）前台服务通知。
     */
    fun startForeground(title: CharSequence, text: CharSequence)

    /**
     * 在前台服务已启动的前提下更新通知文案。
     */
    fun updateForeground(title: CharSequence, text: CharSequence)

    /**
     * 退出前台并停止代理服务。
     */
    fun stopForeground()
}
