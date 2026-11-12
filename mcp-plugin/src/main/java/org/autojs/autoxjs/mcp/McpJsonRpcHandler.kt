package org.autojs.autoxjs.mcp

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.http.HttpStatusCode
import org.autojs.autoxjs.mcp.tool.ToolDefinition
import org.autojs.autoxjs.mcp.tool.ToolRegistry

data class McpServerInfo(
    val name: String,
    val title: String? = null,
    val version: String? = null,
    val description: String? = null
)

data class RpcHttpResponse(
    val status: HttpStatusCode,
    val body: JsonObject? = null
)

class McpJsonRpcHandler(
    private val registry: ToolRegistry,
    private val serverInfoProvider: () -> McpServerInfo,
    private val instructionsProvider: () -> String? = { null },
    private val protocolVersion: String = PROTOCOL_VERSION
) {
    private val gson = Gson()

    suspend fun handleText(body: String): RpcHttpResponse {
        val json = try {
            JsonParser.parseString(body)
        } catch (e: Exception) {
            return RpcHttpResponse(
                HttpStatusCode.BadRequest,
                errorResponse(null, -32700, "Parse error")
            )
        }
        if (!json.isJsonObject) {
            return RpcHttpResponse(
                HttpStatusCode.BadRequest,
                errorResponse(null, -32600, "Invalid Request")
            )
        }
        return try {
            handleJson(json.asJsonObject)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Internal JSON-RPC error", e)
            RpcHttpResponse(
                HttpStatusCode.InternalServerError,
                errorResponse(null, -32603, "Internal error")
            )
        }
    }

    /** 传输层异常时给客户端一个符合 JSON-RPC 规范的错误体，而不是空的 HTTP 500。 */
    fun internalErrorResponse(message: String = "Internal error"): JsonObject =
        errorResponse(null, -32603, message)

    suspend fun handleJson(json: JsonObject): RpcHttpResponse {
        val jsonrpc = json.get("jsonrpc")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        if (jsonrpc != "2.0") {
            return RpcHttpResponse(HttpStatusCode.BadRequest, errorResponse(null, -32600, "Invalid Request"))
        }

        val methodElement = json.get("method")
        val idElement = json.get("id")

        if (methodElement == null) {
            val hasResultOrError = json.has("result") || json.has("error")
            return if (hasResultOrError) {
                RpcHttpResponse(HttpStatusCode.Accepted)
            } else {
                RpcHttpResponse(HttpStatusCode.BadRequest, errorResponse(null, -32600, "Invalid Request"))
            }
        }

        if (!methodElement.isJsonPrimitive || !methodElement.asJsonPrimitive.isString) {
            return RpcHttpResponse(HttpStatusCode.BadRequest, errorResponse(null, -32600, "Invalid Request"))
        }

        val method = methodElement.asString
        val params = json.get("params")

        if (idElement == null || idElement.isJsonNull) {
            handleNotification(method, params)
            return RpcHttpResponse(HttpStatusCode.Accepted)
        }

        val response = handleRequest(method, idElement, params)
        return RpcHttpResponse(HttpStatusCode.OK, response)
    }

    private fun handleNotification(method: String, params: JsonElement?) {
        when (method) {
            "notifications/initialized" -> Unit
            else -> Unit
        }
    }

    private suspend fun handleRequest(method: String, id: JsonElement, params: JsonElement?): JsonObject {
        return when (method) {
            "initialize" -> handleInitialize(id, params)
            "ping" -> successResponse(id, JsonObject())
            "tools/list" -> handleToolsList(id, params)
            "tools/call" -> handleToolsCall(id, params)
            else -> errorResponse(id, -32601, "Method not found")
        }
    }

    private fun handleInitialize(id: JsonElement, params: JsonElement?): JsonObject {
        if (params == null || !params.isJsonObject) {
            return errorResponse(id, -32602, "Invalid params")
        }
        val clientVersion = params.asJsonObject.get("protocolVersion")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
        // initialize 必须回服务端实际支持的版本，不能回显客户端版本：
        // 回显会让客户端误以为服务端支持任意版本，后续按该版本发来的通知/能力
        // 请求本端根本不认识。客户端版本不受支持时由客户端决定是否继续。
        if (clientVersion != null && clientVersion != protocolVersion) {
            Log.i(TAG, "client protocolVersion=$clientVersion unsupported, replying $protocolVersion")
        }
        val info = serverInfoProvider()
        val result = JsonObject().apply {
            addProperty("protocolVersion", protocolVersion)
            add("capabilities", JsonObject().apply {
                add("tools", JsonObject().apply {
                    addProperty("listChanged", false)
                })
            })
            add("serverInfo", JsonObject().apply {
                addProperty("name", info.name)
                info.title?.let { addProperty("title", it) }
                info.version?.let { addProperty("version", it) }
                info.description?.let { addProperty("description", it) }
            })
            // MCP 规范中的服务级使用须知：客户端（如 Claude Desktop）会把它注入模型上下文，
            // 用来告诉 AI 本服务的前置条件、工具能力与已知坑。
            instructionsProvider()?.takeIf { it.isNotBlank() }?.let {
                addProperty("instructions", it)
            }
        }
        return successResponse(id, result)
    }

    private fun handleToolsList(id: JsonElement, params: JsonElement?): JsonObject {
        if (params != null && !params.isJsonObject) {
            return errorResponse(id, -32602, "Invalid params")
        }
        val toolsJson = JsonArray()
        registry.definitions().forEach { toolsJson.add(toolToJson(it)) }
        val result = JsonObject().apply {
            add("tools", toolsJson)
        }
        return successResponse(id, result)
    }

    private suspend fun handleToolsCall(id: JsonElement, params: JsonElement?): JsonObject {
        if (params == null || !params.isJsonObject) {
            return errorResponse(id, -32602, "Invalid params")
        }
        val paramsObj = params.asJsonObject
        val name = paramsObj.get("name")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: return errorResponse(id, -32602, "Invalid params")
        val args = when {
            paramsObj.get("arguments") == null -> JsonObject()
            paramsObj.get("arguments").isJsonObject -> paramsObj.get("arguments").asJsonObject
            else -> return errorResponse(id, -32602, "Invalid params")
        }

        Log.i(TAG, "tools/call id=$id name=$name argsKeys=${args.keySet().joinToString(",")}")
        val response = registry.invoke(name, args)
        Log.i(TAG, "tools/call id=$id name=$name ok=${response.ok} error=${response.errorCode}")
        val content = JsonArray()
        // 图像块放前面：客户端渲染顺序与 content 顺序一致，让模型先看到图再看元数据。
        if (response.ok) {
            response.images.forEach { image ->
                content.add(JsonObject().apply {
                    addProperty("type", "image")
                    addProperty("data", image.data)
                    addProperty("mimeType", image.mimeType)
                })
            }
        }
        val text = if (response.ok) {
            response.data?.let { data ->
                if (data is String) data else gson.toJson(data)
            } ?: "ok"
        } else {
            response.errorMessage ?: response.errorCode ?: "error"
        }
        content.add(JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", text)
        })

        val result = JsonObject().apply {
            add("content", content)
            addProperty("isError", !response.ok)
        }
        return successResponse(id, result)
    }

    private fun toolToJson(definition: ToolDefinition): JsonObject {
        return JsonObject().apply {
            addProperty("name", definition.name)
            definition.title?.let { addProperty("title", it) }
            addProperty("description", definition.description)
            add("inputSchema", definition.inputSchema)
            definition.outputSchema?.let { add("outputSchema", it) }
        }
    }

    private fun successResponse(id: JsonElement, result: JsonObject): JsonObject {
        return JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id)
            add("result", result)
        }
    }

    private fun errorResponse(id: JsonElement?, code: Int, message: String): JsonObject {
        return JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id ?: JsonNull.INSTANCE)
            add("error", JsonObject().apply {
                addProperty("code", code)
                addProperty("message", message)
            })
        }
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-11-25"
        private const val TAG = "McpToolCall"
    }
}
