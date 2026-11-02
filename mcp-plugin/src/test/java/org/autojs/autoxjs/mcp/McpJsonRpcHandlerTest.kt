package org.autojs.autoxjs.mcp

import com.google.gson.JsonParser
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.autojs.autoxjs.mcp.tool.McpTool
import org.autojs.autoxjs.mcp.tool.ToolDefinition
import org.autojs.autoxjs.mcp.tool.ToolRegistry
import org.autojs.autoxjs.mcp.tool.ToolSchemas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpJsonRpcHandlerTest {
    private fun handlerWithTool(): McpJsonRpcHandler {
        val registry = ToolRegistry()
        registry.register(
            ToolDefinition(
                name = "echo",
                title = "Echo",
                description = "Echo arguments",
                inputSchema = ToolSchemas.objectSchema(
                    mapOf("value" to ToolSchemas.stringSchema("Echo value."))
                )
            ),
            McpTool { params -> McpResponse.ok(mapOf("echo" to params?.get("value")?.asString)) }
        )
        return McpJsonRpcHandler(
            registry,
            serverInfoProvider = { McpServerInfo(name = "TestServer", title = "Test", version = "1.0.0") }
        )
    }

    @Test
    fun initializeReturnsCapabilities() = runBlocking {
        val handler = handlerWithTool()
        val request = JsonParser.parseString(
            """
            {
              "jsonrpc": "2.0",
              "id": 1,
              "method": "initialize",
              "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": { "name": "TestClient" }
              }
            }
            """.trimIndent()
        ).asJsonObject
        val response = handler.handleJson(request)
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body
        assertNotNull(body)
        val result = body!!.getAsJsonObject("result")
        assertEquals("2024-11-05", result.get("protocolVersion").asString)
        assertTrue(result.getAsJsonObject("capabilities").has("tools"))
    }

    @Test
    fun listToolsReturnsDefinitions() = runBlocking {
        val handler = handlerWithTool()
        val request = JsonParser.parseString(
            """
            {
              "jsonrpc": "2.0",
              "id": "list-1",
              "method": "tools/list",
              "params": {}
            }
            """.trimIndent()
        ).asJsonObject
        val response = handler.handleJson(request)
        assertEquals(HttpStatusCode.OK, response.status)
        val tools = response.body!!
            .getAsJsonObject("result")
            .getAsJsonArray("tools")
        assertEquals(1, tools.size())
        assertEquals("echo", tools[0].asJsonObject.get("name").asString)
    }

    @Test
    fun callToolReturnsResult() = runBlocking {
        val handler = handlerWithTool()
        val request = JsonParser.parseString(
            """
            {
              "jsonrpc": "2.0",
              "id": 7,
              "method": "tools/call",
              "params": {
                "name": "echo",
                "arguments": { "value": "hello" }
              }
            }
            """.trimIndent()
        ).asJsonObject
        val response = handler.handleJson(request)
        assertEquals(HttpStatusCode.OK, response.status)
        val result = response.body!!.getAsJsonObject("result")
        assertFalse(result.get("isError").asBoolean)
        val content = result.getAsJsonArray("content")
        assertTrue(content[0].asJsonObject.get("text").asString.contains("hello"))
    }

    @Test
    fun unknownToolReturnsErrorResult() = runBlocking {
        val handler = handlerWithTool()
        val request = JsonParser.parseString(
            """
            {
              "jsonrpc": "2.0",
              "id": 9,
              "method": "tools/call",
              "params": {
                "name": "missing",
                "arguments": {}
              }
            }
            """.trimIndent()
        ).asJsonObject
        val response = handler.handleJson(request)
        val result = response.body!!.getAsJsonObject("result")
        assertTrue(result.get("isError").asBoolean)
    }

    @Test
    fun initializedNotificationReturnsAccepted() = runBlocking {
        val handler = handlerWithTool()
        val request = JsonParser.parseString(
            """
            {
              "jsonrpc": "2.0",
              "method": "notifications/initialized"
            }
            """.trimIndent()
        ).asJsonObject
        val response = handler.handleJson(request)
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertNull(response.body)
    }

    @Test
    fun toolImagesBecomeImageContentBlocksBeforeText() = runBlocking {
        val registry = ToolRegistry()
        registry.register(
            ToolDefinition(
                name = "shot",
                title = "Shot",
                description = "Return an inline image",
                inputSchema = ToolSchemas.emptyObject()
            ),
            McpTool {
                McpResponse.ok(
                    mapOf("width" to 324, "height" to 720),
                    listOf(McpImage(data = "QUJD", mimeType = "image/jpeg"))
                )
            }
        )
        val handler = McpJsonRpcHandler(
            registry,
            serverInfoProvider = { McpServerInfo(name = "TestServer") }
        )
        val request = JsonParser.parseString(
            """
            {
              "jsonrpc": "2.0",
              "id": 11,
              "method": "tools/call",
              "params": { "name": "shot", "arguments": {} }
            }
            """.trimIndent()
        ).asJsonObject
        val response = handler.handleJson(request)
        val content = response.body!!.getAsJsonObject("result").getAsJsonArray("content")
        assertEquals(2, content.size())
        val imageBlock = content[0].asJsonObject
        assertEquals("image", imageBlock.get("type").asString)
        assertEquals("QUJD", imageBlock.get("data").asString)
        assertEquals("image/jpeg", imageBlock.get("mimeType").asString)
        val textBlock = content[1].asJsonObject
        assertEquals("text", textBlock.get("type").asString)
        assertTrue(textBlock.get("text").asString.contains("\"width\":324"))
        // base64 不应再出现在文本块里，否则 token 成本翻数倍。
        assertFalse(textBlock.get("text").asString.contains("QUJD"))
    }
}
