package org.autojs.autoxjs.mcp

import android.app.Activity
import android.os.Bundle

/**
 * 占位组件，仅用于让宿主通过 `org.autojs.plugin.sdk.PLUGIN` action 枚举到本插件。
 *
 * 插件本身不提供任何界面：设置页由宿主按 [McpConfigSpec] 声明的配置项渲染。
 * 本组件不导出、没有启动图标，误启动时立即结束。
 */
class McpPluginMarkerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
