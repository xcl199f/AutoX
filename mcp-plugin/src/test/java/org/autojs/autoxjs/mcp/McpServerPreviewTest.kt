package org.autojs.autoxjs.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * logcat 里的报文摘要不能包含任何原文：请求体可能含 token / 脚本文本，
 * 响应体可能含执行结果与内联截图（单张 30~110 KB），截断到几百字符也会泄露。
 */
class McpServerPreviewTest {

    private fun preview(payload: String) = McpServer.summarizeForLog(payload)

    @Test
    fun shortPayloadIsSummarized() {
        val payload = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
        assertEquals("${payload.length} chars", preview(payload))
    }

    @Test
    fun requestBodyNeverLeaksIntoLog() {
        val payload = """{"params":{"name":"run_script","arguments":{"script":"var token='secret'"}}}"""
        val logged = preview(payload)
        assertFalse("原文不应进入日志", logged.contains("secret"))
        assertFalse("原文不应进入日志", logged.contains("run_script"))
    }

    @Test
    fun realScreenshotPayloadIsNotLogged() {
        val base64 = "/9j/4AAQSkZJRgABAQAAAQABAAD/4gHYSUNDX1BST0ZJTEUAAQEAAAHIAAAAAAQwAABtbnRy" +
            "UkdCIFhZWiAH4AABAAEAAAAAAABhY3Nw".repeat(200)
        val payload = """{"result":{"content":[{"type":"image","data":"$base64"}]}}"""
        val logged = preview(payload)
        assertFalse(logged.contains("/9j/4AAQ"))
        assertEquals("${payload.length} chars", logged)
    }

    @Test
    fun oversizedPayloadStillHasBoundedLog() {
        val payload = "x".repeat(5_000)
        assertEquals("5000 chars", preview(payload))
    }

    @Test
    fun emptyPayload() {
        assertEquals("0 chars", preview(""))
    }
}
