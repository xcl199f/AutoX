package org.autojs.autoxjs.mcp

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 生成 PC 侧可直接访问的下载地址。
 *
 * 截图默认落在宿主私有 cacheDir（`/data/data/<pkg>/cache/`），PC 上的客户端无法直接读取，
 * 过去只能靠 base64 内联取图。这里配合 [McpServer] 的 `GET /files/{name}` 路由给出一个
 * HTTP 地址：客户端普通下载即可，既省 JSON 体积，也不需要开启「允许返回 Base64 图像」。
 */
object McpUrls {

    private const val TAG = "McpUrls"

    /** 绑定到这些地址时无法作为对外访问地址，需要替换成实际网卡 IP。 */
    private val WILDCARD_HOSTS = setOf("", "0.0.0.0", "::", "[::]", "*")

    /** 服务根地址，例如 `http://192.168.110.156:27190`。 */
    fun baseUrl(config: McpConfig): String {
        val host = config.host.trim()
        val resolved = if (host in WILDCARD_HOSTS) lanAddress() ?: "127.0.0.1" else host
        return "http://$resolved:${config.port}"
    }

    /**
     * 把设备本地文件路径映射成下载地址。
     *
     * 只取最后一段文件名——服务端 `/files/{name}` 仅允许 cacheDir 下的截图，
     * 传入其它目录的路径会得到一个 404，不会造成目录穿越。
     */
    fun fileUrl(baseUrl: String, path: String?): String? {
        val name = path?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return null
        return "$baseUrl/files/$name"
    }

    /**
     * 监听地址是通配符时，挑一个局域网 IPv4 作为对外地址。
     *
     * 优先私有网段（192.168/10/172.16-31），跳过回环、链路本地与蜂窝网卡，
     * 避免把运营商地址或虚拟网卡地址暴露给客户端。
     */
    private fun lanAddress(): String? {
        val candidates = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.orEmpty()
                // rmnet 是蜂窝数据、dummy 是系统占位网卡，都不是局域网入口
                if (name.startsWith("rmnet") || name.startsWith("dummy")) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address !is Inet4Address) continue
                    if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                    val host = address.hostAddress?.substringBefore('%')
                    if (!host.isNullOrBlank()) {
                        candidates.add(host)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to resolve lan address", e)
        }
        return candidates.firstOrNull { isPrivate(it) } ?: candidates.firstOrNull()
    }

    private fun isPrivate(host: String): Boolean =
        host.startsWith("192.168.") ||
            host.startsWith("10.") ||
            PRIVATE_172.containsMatchIn(host)

    private val PRIVATE_172 = Regex("^172\\.(1[6-9]|2\\d|3[01])\\.")
}
