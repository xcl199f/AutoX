package org.autojs.autoxjs.mcp

import android.os.Process
import com.stardust.autojs.AutoJs
import com.stardust.autojs.runtime.ScriptRuntimeV2

class McpRuntimeProvider {
    private val lock = Any()
    private var runtime: ScriptRuntimeV2? = null

    fun getRuntime(): ScriptRuntimeV2 {
        synchronized(lock) {
            val current = runtime
            if (current != null) {
                return current
            }
            val autoJs = try {
                AutoJs.instance
            } catch (e: UninitializedPropertyAccessException) {
                // AutoJs 只在 :script 进程初始化（见 App.init）。
                // 若这里抛异常，说明 MCP 服务跑在了主进程，属于宿主加载策略的问题。
                throw IllegalStateException(
                    "AutoJs is not initialized (pid=${Process.myPid()})", e
                )
            }
            val created = autoJs.createRuntimeBuilder().build().apply { init() }
            runtime = created
            return created
        }
    }

    fun close() {
        synchronized(lock) {
            runtime?.onExit()
            runtime = null
        }
    }
}
