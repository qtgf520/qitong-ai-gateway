package com.qtwl.gateway.network

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder

/**
 * 代理链接解析器
 * 纯解析不涉及任何网络操作，不会崩溃
 */
object ProxyLinkParser {

    data class ProxyLinkInfo(
        val name: String = "未命名",
        val type: String = "HTTP",
        val host: String = "",
        val port: Int = 443,
        val uuid: String = "",
        val encryption: String = "",
        val network: String = "tcp",
        val security: String = "none",
        val path: String = "",
        val hostHeader: String = "",
        val alpn: String = "",
        val fingerprint: String = "chrome",
        val publicKey: String = "",
        val shortId: String = "",
        val flow: String = "",
        val aid: String = "0"
    )

    fun parse(link: String): ProxyLinkInfo? {
        if (link.isBlank()) return null
        return try {
            when {
                link.startsWith("vmess://") -> parseVmess(link)
                link.startsWith("ss://") -> parseSs(link)
                link.startsWith("vless://") -> parseVless(link)
                link.startsWith("trojan://") || link.startsWith("hysteria2://") || link.startsWith("hy2://") -> parseBasic(link)
                else -> null
            }
        } catch (_: Exception) { null }
    }

    fun parseBatch(text: String): List<ProxyLinkInfo> {
        if (text.isBlank()) return emptyList()
        try {
            val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
            val direct = lines.filter { l ->
                l.startsWith("vmess://") || l.startsWith("ss://") || l.startsWith("vless://") ||
                l.startsWith("trojan://") || l.startsWith("hysteria2://") || l.startsWith("hy2://")
            }
            if (direct.isNotEmpty()) return direct.mapNotNull { parse(it) }

            val clean = text.trim().replace(Regex("\\s"), "")
            if (clean.length > 20) {
                val bytes = try { Base64.decode(clean, Base64.DEFAULT) } catch (_: Exception) { null }
                if (bytes != null) {
                    val decoded = String(bytes, Charsets.UTF_8)
                    return decoded.lines().map { it.trim() }.filter { it.isNotBlank() }
                        .filter { l -> l.startsWith("vmess://") || l.startsWith("ss://") || l.startsWith("vless://") || l.startsWith("trojan://") || l.startsWith("hysteria2://") || l.startsWith("hy2://") }
                        .mapNotNull { parse(it) }
                }
            }
        } catch (_: Exception) { }
        return emptyList()
    }

    private fun parseVmess(link: String): ProxyLinkInfo? {
        val b64 = link.removePrefix("vmess://").trim()
        val jsonStr = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
        val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(jsonStr).jsonObject
        return ProxyLinkInfo(
            name = obj["ps"]?.jsonPrimitive?.content ?: "VMESS节点",
            type = "VMESS",
            host = obj["add"]?.jsonPrimitive?.content ?: "",
            port = obj["port"]?.jsonPrimitive?.content?.toIntOrNull() ?: 443,
            uuid = obj["id"]?.jsonPrimitive?.content ?: "",
            encryption = obj["scy"]?.jsonPrimitive?.content ?: obj["security"]?.jsonPrimitive?.content ?: "auto",
            network = obj["net"]?.jsonPrimitive?.content ?: "tcp",
            security = if (obj["tls"]?.jsonPrimitive?.content == "tls") "tls" else "none",
            path = obj["path"]?.jsonPrimitive?.content ?: "",
            hostHeader = obj["host"]?.jsonPrimitive?.content ?: "",
            alpn = obj["alpn"]?.jsonPrimitive?.content ?: "",
            fingerprint = obj["fp"]?.jsonPrimitive?.content ?: "chrome",
            aid = obj["aid"]?.jsonPrimitive?.content ?: "0"
        )
    }

    private fun parseSs(link: String): ProxyLinkInfo? {
        var raw = link.removePrefix("ss://").trim()
        val namePart = if (raw.contains("#")) {
            val p = raw.split("#", limit = 2); raw = p[0]
            try { URLDecoder.decode(p[1], "UTF-8") } catch (_: Exception) { p[1] }
        } else "SS节点"

        val full = if (raw.contains("@")) {
            val ai = raw.indexOf("@")
            val b64p = raw.substring(0, ai)
            val hp = raw.substring(ai + 1)
            val dec = try { String(Base64.decode(b64p.replace(Regex("[^A-Za-z0-9+/=]"), ""), Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { b64p }
            "$dec@$hp"
        } else {
            try { String(Base64.decode(raw.replace(Regex("[^A-Za-z0-9+/=]"), ""), Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { raw }
        }
        val ai = full.indexOf("@")
        if (ai < 0) return ProxyLinkInfo(name = namePart, type = "SS", host = "", port = 443)
        val mp = full.substring(0, ai)
        val hp = full.substring(ai + 1)
        val mi = mp.indexOf(":")
        val method = if (mi >= 0) mp.substring(0, mi) else mp
        val host = hp.substringBefore(":").substringBefore("?").substringBefore("#").trim('[', ']')
        return ProxyLinkInfo(
            name = namePart, type = "SS", host = host,
            port = hp.substringAfter(":").substringBefore("?").substringBefore("#").toIntOrNull() ?: 443,
            uuid = method, encryption = method
        )
    }

    private fun parseVless(link: String): ProxyLinkInfo? {
        var raw = link.removePrefix("vless://").trim()
        val namePart = if (raw.contains("#")) {
            val p = raw.split("#", limit = 2); raw = p[0]
            try { URLDecoder.decode(p[1], "UTF-8") } catch (_: Exception) { p[1] }
        } else "VLESS节点"
        val ai = raw.indexOf("@")
        if (ai < 0) return null
        val uuid = raw.substring(0, ai)
        val rest = raw.substring(ai + 1)
        val qi = rest.indexOf("?")
        val hp = if (qi >= 0) rest.substring(0, qi) else rest
        val query = if (qi >= 0) rest.substring(qi + 1) else ""
        val params = query.split("&").mapNotNull { s ->
            val e = s.indexOf("="); if (e >= 0) s.substring(0, e) to s.substring(e + 1) else null
        }.toMap()

        val host = if (hp.startsWith("[")) { val cb = hp.indexOf("]"); if (cb >= 0) hp.substring(1, cb) else hp }
        else hp.substringBefore(":")
        val portStr = if (hp.startsWith("[")) { val cb = hp.indexOf("]"); if (cb >= 0) hp.substring(cb + 1).removePrefix(":") else "443" }
        else hp.substringAfter(":", "443")

        return ProxyLinkInfo(
            name = namePart, type = "VLESS", host = host, port = portStr.toIntOrNull() ?: 443,
            uuid = uuid, encryption = params["encryption"] ?: "none",
            network = params["type"] ?: "tcp", security = params["security"] ?: "none",
            path = params["path"] ?: "", hostHeader = params["sni"] ?: params["host"] ?: "",
            alpn = params["alpn"] ?: "", fingerprint = params["fp"] ?: params["fingerprint"] ?: "chrome",
            publicKey = params["pbk"] ?: params["publicKey"] ?: "",
            shortId = params["sid"] ?: params["shortId"] ?: "", flow = params["flow"] ?: ""
        )
    }

    private fun parseBasic(link: String): ProxyLinkInfo? {
        val proto = when {
            link.startsWith("trojan://") -> "Trojan"
            link.startsWith("hy2://") || link.startsWith("hysteria2://") -> "Hysteria2"
            else -> return null
        }
        val prefix = if (proto == "Trojan") "trojan://" else if (link.startsWith("hy2://")) "hy2://" else "hysteria2://"
        val stripped = link.removePrefix(prefix)
        val name = try { URLDecoder.decode(stripped.substringAfterLast("#", proto), "UTF-8") } catch (_: Exception) { stripped.substringAfterLast("#", proto) }
        val hp = stripped.substringAfter("@").substringBefore("?").substringBefore("#")
        return ProxyLinkInfo(
            name = name, type = proto, host = hp.substringBefore(":"),
            port = hp.substringAfter(":").toIntOrNull() ?: 443,
            uuid = stripped.substringBefore("@"), encryption = "none"
        )
    }
}