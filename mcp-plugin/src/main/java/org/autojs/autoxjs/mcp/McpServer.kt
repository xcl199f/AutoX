package org.autojs.autoxjs.mcp

import android.content.Context
import android.content.pm.PackageInfo
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import org.autojs.autoxjs.mcp.tool.ToolRegistry
import java.io.File
import java.net.NetworkInterface

/**
 * Lightweight MCP server wrapper based on Ktor.
 */
class McpServer internal constructor(
    private val appContext: Context,
    private val registry: ToolRegistry,
    private val healthSupervisor: McpSelfHealthSupervisor
) {
    private val gson = Gson()
    private var engine: ApplicationEngine? = null
    private val jsonRpcHandler = McpJsonRpcHandler(
        registry,
        serverInfoProvider = { buildServerInfo() },
        instructionsProvider = { McpInstructions.TEXT }
    )

    val isRunning: Boolean
        @Synchronized get() = engine != null

    /**
     * 最后一次「真实使用」的时间戳，用于空闲休眠判断。
     *
     * 只统计 `/mcp` 与 `/files` 请求；`/healthz` 是服务自检（每 20 秒一次），
     * 若把它算进来就永远不会空闲，休眠功能会失效。
     */
    @Volatile
    var lastActivityAt: Long = System.currentTimeMillis()
        private set

    fun markActivity() {
        lastActivityAt = System.currentTimeMillis()
    }

    @Synchronized
    fun start(config: McpConfig): Boolean {
        stop()
        if (!config.enabled) return false
        Log.i(TAG, "Starting MCP server on ${config.host}:${config.port}")
        var candidate: ApplicationEngine? = null
        val identity = healthSupervisor.beginEngineStart()
        try {
            val newEngine = embeddedServer(CIO, port = config.port, host = config.host) {
                install(WebSockets)
                routing {
                    post("/mcp") {
                        markActivity()
                        try {
                            Log.d(TAG, "Received POST /mcp request")
                            if (!isOriginAllowed(config, call.request.headers["Origin"])) {
                                Log.d(TAG, "Origin not allowed")
                                call.respond(HttpStatusCode.Forbidden)
                                return@post
                            }

                            if (!authorize(config, call.request.headers["X-Token"])) {
                                Log.d(TAG, "Unauthorized")
                                call.respond(HttpStatusCode.Unauthorized)
                                return@post
                            }

                            val body = call.receiveText()
                            Log.d(TAG, "Received body: ${previewForLog(body)}")
                            val response = jsonRpcHandler.handleText(body)
                            Log.d(TAG, "Response status: ${response.status}")
                            if (response.body == null) {
                                call.respond(response.status)
                            } else {
                                val jsonResponse = gson.toJson(response.body)
                                Log.d(TAG, "Sending JSON response: ${previewForLog(jsonResponse)}")
                                call.respondText(
                                    jsonResponse,
                                    ContentType.Application.Json,
                                    response.status
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error handling POST /mcp", e)
                            call.respond(HttpStatusCode.InternalServerError)
                        }
                    }

                    get("/mcp") {
                        call.respond(HttpStatusCode.MethodNotAllowed)
                    }

                    get("/healthz") {
                        // ktor 2.0.x 的 RequestConnectionPoint 只暴露 remoteHost，
                        // 且这里取 local（套接字原始对端）而非 origin，避免被转发头影响。
                        if (!isSameDeviceRemoteAddress(call.request.local.remoteHost)) {
                            call.respond(HttpStatusCode.Forbidden)
                            return@get
                        }
                        call.respondText(
                            gson.toJson(healthSupervisor.payload(identity)),
                            ContentType.Application.Json,
                            HttpStatusCode.OK
                        )
                    }

                    get("/files/{name}") {
                        // 下载截图同样算「在用」，否则连续看图期间会被判定为空闲。
                        markActivity()
                        // 截图落在宿主私有 cacheDir，PC 侧读不到，这里提供一条只读下载通道。
                        // 与 /mcp 共用同一个访问令牌：令牌为空时放行（保持既有行为）。
                        if (!authorize(config, call.request.headers["X-Token"])) {
                            call.respond(HttpStatusCode.Unauthorized)
                            return@get
                        }
                        val name = call.parameters["name"].orEmpty()
                        // 只允许本服务自己写入的截图文件名，天然杜绝 ../ 目录穿越。
                        if (!SCREENSHOT_FILE_NAME.matches(name)) {
                            call.respond(HttpStatusCode.BadRequest)
                            return@get
                        }
                        val file = File(appContext.cacheDir, name)
                        if (!file.isFile) {
                            call.respond(HttpStatusCode.NotFound)
                            return@get
                        }
                        call.respondFile(file)
                    }

                    get("/") {
                        call.respondText("MCP Server Running", ContentType.Text.Plain)
                    }
                }
            }
            candidate = newEngine
            Log.i(TAG, "Engine created, starting...")
            newEngine.start(wait = false)
            engine = newEngine
            healthSupervisor.engineStartSucceeded(identity)
            Log.i(TAG, "Engine started successfully")
            Log.i(TAG, "MCP server started on ${config.host}:${config.port}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MCP server", e)
            try {
                candidate?.stop(0, 1000)
            } catch (stopError: Exception) {
                Log.w(TAG, "Failed to clean up MCP server after start failure", stopError)
            }
            engine = null
            healthSupervisor.engineStartFailed(identity)
            return false
        }
    }

    @Synchronized
    fun stop() {
        try {
            engine?.stop(1000, 2000)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        } finally {
            engine = null
        }
    }

    private fun authorize(config: McpConfig, tokenHeader: String?): Boolean {
        val expected = config.token
        return expected.isNullOrBlank() || expected == tokenHeader
    }

    private fun isOriginAllowed(config: McpConfig, originHeader: String?): Boolean {
        if (originHeader.isNullOrBlank()) {
            return true
        }
        val host = try {
            Uri.parse(originHeader).host
        } catch (_: Exception) {
            null
        } ?: return false

        val allowedHosts = if (config.allowNetwork) {
            localHosts()
        } else {
            setOf("localhost", "127.0.0.1", "::1")
        }
        return allowedHosts.contains(host)
    }

    private fun localHosts(): Set<String> {
        val hosts = mutableSetOf("localhost", "127.0.0.1", "::1")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    val host = address.hostAddress?.substringBefore('%')
                    if (!host.isNullOrBlank()) {
                        hosts.add(host)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve local addresses", e)
        }
        return hosts
    }

    private fun buildServerInfo(): McpServerInfo {
        val name = "AutoX MCP"
        val versionName = try {
            val info: PackageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            info.versionName
        } catch (_: Exception) {
            null
        }
        return McpServerInfo(
            name = name,
            title = name,
            version = versionName,
            description = "AutoX embedded MCP tools server"
        )
    }

    companion object {
        private const val TAG = "McpServer"

        /** 只放行截图工具写入的文件名，避免任意文件被下载。 */
        private val SCREENSHOT_FILE_NAME = Regex("^mcp-screenshot-\\d+\\.png$")

        /** 日志里保留的报文长度上限，超出部分只记总长。 */
        private const val LOG_PREVIEW_LIMIT = 512

        /**
         * 报文里的长 base64（内联截图，单张 30~110 KB）在写日志前替换成占位符。
         *
         * 直接把完整响应写进 logcat 有两个代价：一次请求就写掉上百 KB，
         * 足以冲掉整个日志缓冲区（排查问题前因后果全丢）；同时每次都要
         * 拼一个大字符串。这里保留报文结构，只抹掉大块数据。
         *
         * 用 `[^"]` 而不是枚举 base64 字符集：编码方式（标准 / URL-safe）变了
         * 也不会漏掉，只要字段里是 256 个以上的连续非引号字符就认定是大块数据。
         */
        private val LONG_INLINE_DATA = Regex("\"data\"\\s*:\\s*\"([^\"]{256,})\"")

        internal fun previewForLog(payload: String): String {
            val masked = payload.replace(LONG_INLINE_DATA) { match ->
                val dataLength = match.groupValues[1].length
                "\"data\":\"<inline data $dataLength chars>\""
            }
            if (masked.length <= LOG_PREVIEW_LIMIT) {
                return masked
            }
            return masked.take(LOG_PREVIEW_LIMIT) + "...(total ${payload.length} chars)"
        }
    }
}
