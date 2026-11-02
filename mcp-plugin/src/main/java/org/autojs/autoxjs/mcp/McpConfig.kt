package org.autojs.autoxjs.mcp

/**
 * Configuration for the MCP in-app server.
 */
data class McpConfig(
    val enabled: Boolean = false,
    val host: String = "0.0.0.0",
    val port: Int = 27190,
    val token: String? = null,
    val allowBase64: Boolean = false,
    val allowNetwork: Boolean = true,
    /** 长时间没有请求时是否停掉 HTTP 监听，释放端口与线程。 */
    val idleShutdown: Boolean = true,
    /** 空闲多久算「长时间未使用」，单位分钟。 */
    val idleMinutes: Int = DEFAULT_IDLE_MINUTES
) {
    companion object {
        const val DEFAULT_IDLE_MINUTES = 30
        const val MIN_IDLE_MINUTES = 5
        const val MAX_IDLE_MINUTES = 720
    }
}
