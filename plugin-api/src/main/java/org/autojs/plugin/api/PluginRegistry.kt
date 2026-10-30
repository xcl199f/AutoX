package org.autojs.plugin.api

import android.content.Context

/**
 * 插件入口契约。
 *
 * 插件 APK 需要在其 AndroidManifest.xml 的 `<application>` 节点内声明入口类：
 *
 * ```xml
 * <meta-data
 *     android:name="org.autojs.plugin.sdk.registry"
 *     android:value="com.example.mcp.McpPluginRegistry" />
 * ```
 *
 * 该类必须提供无参构造函数并实现本接口。宿主读取 meta-data 后反射实例化，
 * 再调用 [create] 取得插件实例。
 */
interface PluginRegistry {

    /**
     * 创建插件实例。每次加载只会调用一次。
     */
    fun create(): AutoJsPlugin
}
