package org.autojs.autoxjs.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * logcat 只应记录可读的报文头部：内联截图的 base64 必须被抹掉，
 * 否则一次请求就会写掉上百 KB，冲掉整个日志缓冲区。
 */
class McpServerPreviewTest {

    private fun preview(payload: String) = McpServer.previewForLog(payload)

    @Test
    fun shortPayloadIsKeptVerbatim() {
        val payload = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
        assertEquals(payload, preview(payload))
    }

    @Test
    fun longBase64IsReplacedWithPlaceholder() {
        val base64 = "A".repeat(40_000)
        val payload = """{"type":"image","data":"$base64","mimeType":"image/jpeg"}"""
        val logged = preview(payload)
        assertFalse("base64 内容不应进入日志", logged.contains("AAAAAAAAAAAAAAAA"))
        assertTrue("应保留占位符说明长度", logged.contains("<inline data 40000 chars>"))
        assertTrue("应保留报文结构", logged.contains("\"mimeType\":\"image/jpeg\""))
        assertTrue("日志长度必须受控", logged.length < 600)
    }

    @Test
    fun realScreenshotPayloadIsMasked() {
        // 真实截图响应里 base64 含 + / = 等字符，且后面紧跟其它字段。
        val base64 = "/9j/4AAQSkZJRgABAQAAAQABAAD/4gHYSUNDX1BST0ZJTEUAAQEAAAHIAAAAAAQwAABtbnRy" +
            "UkdCIFhZWiAH4AABAAEAAAAAAABhY3Nw".repeat(200)
        val payload = """{"jsonrpc":"2.0","id":9,"result":{"content":[{"type":"image",""" +
            """"data":"$base64","mimeType":"image/jpeg"},{"type":"text","text":"{}"}]}}"""
        val logged = preview(payload)
        assertFalse("真实 base64 也必须被抹掉", logged.contains("/9j/4AAQ"))
        assertTrue(logged.contains("<inline data ${base64.length} chars>"))
        assertTrue(logged.length < 600)
    }

    @Test
    fun oversizedPayloadIsTruncatedWithTotalLength() {
        val payload = "x".repeat(5_000)
        val logged = preview(payload)
        assertTrue(logged.length < 600)
        assertTrue(logged.endsWith("...(total 5000 chars)"))
    }

    @Test
    fun shortBase64IsNotMasked() {
        // 短数据（如 id、hash）不应被误伤。
        val payload = """{"data":"QUJD","type":"image"}"""
        assertEquals(payload, preview(payload))
    }
}
