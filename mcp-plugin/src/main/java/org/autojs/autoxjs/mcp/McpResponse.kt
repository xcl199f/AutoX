package org.autojs.autoxjs.mcp

/**
 * 一个内联图像内容块，对应 MCP 规范里的 `{ "type": "image", "data": ..., "mimeType": ... }`。
 *
 * 之所以不把 base64 塞进文本 JSON：base64 是高熵字符串，塞文本时几乎每个字符都占一个 token
 * （一张 720 长边的截图约 5500~7000 token）；走 image 内容块时，token 由像素数量决定
 * （约 w*h/750，同尺寸约 1500 token），相差 4 倍以上。
 */
data class McpImage(
    val data: String,
    val mimeType: String
)

/**
 * Standardized response envelope for MCP tools.
 */
data class McpResponse(
    val ok: Boolean,
    val data: Any? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    /** 需要以 image 内容块返回的图像，按顺序排在文本块之前。 */
    val images: List<McpImage> = emptyList()
) {
    companion object {
        fun ok(data: Any? = null, images: List<McpImage> = emptyList()) =
            McpResponse(true, data, null, null, images)

        fun error(code: String, message: String) = McpResponse(false, null, code, message)
    }
}
