package com.qtwl.gateway.network

/**
 * 代理链接解析器 — 仅支持 HTTP/HTTPS/SOCKS5
 */
object ProxyLinkParser {

    data class ProxyLinkInfo(
        val name: String = "未命名",
        val type: String = "HTTP",
        val host: String = "",
        val port: Int = 443
    )

    fun parse(link: String): ProxyLinkInfo? {
        if (link.isBlank()) return null
        return try {
            when {
                link.startsWith("http://") || link.startsWith("https://") || link.startsWith("socks5://") || link.startsWith("socks://") -> parseLink(link)
                else -> null
            }
        } catch (_: Exception) { null }
    }

    fun parseBatch(text: String): List<ProxyLinkInfo> {
        if (text.isBlank()) return emptyList()
        return text.lines().map { it.trim() }.filter { it.isNotBlank() }
            .filter { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("socks5://") || it.startsWith("socks://") }
            .mapNotNull { parse(it) }
    }

    private fun parseLink(link: String): ProxyLinkInfo {
        val name = try { java.net.URLDecoder.decode(link.substringAfterLast("#", ""), "UTF-8") } catch (_: Exception) { "" }
        val cleanLink = link.substringBefore("#")
        val type = when {
            cleanLink.startsWith("https://") -> "HTTPS"
            cleanLink.startsWith("socks5://") || cleanLink.startsWith("socks://") -> "SOCKS5"
            else -> "HTTP"
        }
        val prefixLen = if (type == "HTTPS") 8 else if (type == "HTTP") 7 else if (cleanLink.startsWith("socks5://")) 9 else 7
        val afterProto = cleanLink.substring(prefixLen)
        val hp = afterProto.substringAfter("@", afterProto).substringBefore("/")
        return ProxyLinkInfo(
            name = name.ifBlank { hp },
            type = type,
            host = hp.substringBefore(":"),
            port = hp.substringAfter(":", if (type == "HTTPS") "443" else "80").toIntOrNull() ?: if (type == "HTTPS") 443 else 80
        )
    }
}