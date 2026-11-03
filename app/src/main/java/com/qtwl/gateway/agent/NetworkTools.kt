package com.qtwl.gateway.agent

import com.qtwl.gateway.network.UpstreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 网络工具
 *
 * 让 Agent 能够主动获取外部信息。
 * 出于安全考虑：只允许 http/https，限制响应大小，超时保护。
 */
object NetworkTools {

    private const val MAX_RESPONSE_CHARS = 8000
    private const val TIMEOUT_SECONDS = 15L

    fun all(useProxy: Boolean = true): List<AgentTool> =
        listOf(httpRequestTool(useProxy), readWebpageTool(useProxy))

    private fun httpRequestTool(useProxy: Boolean) = AgentTool(
        name = "http_request",
        description = "发起 HTTP 请求获取网络数据。可用于调用公开 API、查询天气/汇率/新闻等。" +
            "只支持 http 和 https。返回内容会截断到 ${MAX_RESPONSE_CHARS} 字符。",
        parameters = listOf(
            ToolParam(
                name = "url",
                type = "string",
                description = "完整 URL，必须以 http:// 或 https:// 开头"
            ),
            ToolParam(
                name = "method",
                type = "string",
                description = "请求方法",
                required = false,
                enumValues = listOf("GET", "POST")
            ),
            ToolParam(
                name = "body",
                type = "string",
                description = "请求体（POST 时使用，JSON 或表单文本）",
                required = false
            ),
            ToolParam(
                name = "content_type",
                type = "string",
                description = "请求体类型，默认 application/json",
                required = false
            )
        )
    ) { args ->
        val url = args["url"].orEmpty().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@AgentTool ToolResult.fail("只支持 http/https 协议，收到：$url")
        }
        val method = args["method"]?.uppercase()?.takeIf { it.isNotBlank() } ?: "GET"
        val body = args["body"]
        val contentType = args["content_type"]?.takeIf { it.isNotBlank() } ?: "application/json"

        try {
            withContext(Dispatchers.IO) {
                val client = UpstreamClient.getClientForModel(useProxy).newBuilder()
                    .connectTimeout(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                    .callTimeout(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build()

                val builder = Request.Builder()
                    .url(url)
                    .header("User-Agent", "QiTong-AI-Gateway-Agent/1.0")
                    .header("Accept", "*/*")

                val request = when (method) {
                    "POST" -> builder
                        .post((body ?: "").toRequestBody(contentType.toMediaType()))
                        .build()
                    else -> builder.get().build()
                }

                client.newCall(request).execute().use { resp ->
                    val code = resp.code
                    val text = resp.body?.string().orEmpty()
                    val truncated = text.length > MAX_RESPONSE_CHARS
                    val shown = if (truncated) text.take(MAX_RESPONSE_CHARS) else text
                    val header = "HTTP $code ${resp.message}\n" +
                        "Content-Type: ${resp.header("Content-Type") ?: "未知"}\n" +
                        "长度: ${text.length} 字符" +
                        if (truncated) "（已截断）" else "" +
                        "\n---\n"

                    if (code in 200..299) {
                        ToolResult.ok(header + shown)
                    } else {
                        ToolResult.fail(header + shown)
                    }
                }
            }
        } catch (e: Exception) {
            ToolResult.fail("请求失败：${e.message ?: e.javaClass.simpleName}\nURL: $url")
        }
    }

    private fun readWebpageTool(useProxy: Boolean) = AgentTool(
        name = "read_webpage",
        description = "读取网页并提取正文文本。会自动去除 HTML 标签、脚本和样式，" +
            "适合阅读理解文章、文档、搜索结果页。",
        parameters = listOf(
            ToolParam(
                name = "url",
                type = "string",
                description = "网页地址，必须以 http:// 或 https:// 开头"
            )
        )
    ) { args ->
        val url = args["url"].orEmpty().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@AgentTool ToolResult.fail("只支持 http/https 协议，收到：$url")
        }
        try {
            withContext(Dispatchers.IO) {
                val client = UpstreamClient.getClientForModel(useProxy).newBuilder()
                    .connectTimeout(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                    .callTimeout(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
                    )
                    .header("Accept", "text/html,application/xhtml+xml,*/*")
                    .get()
                    .build()

                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext ToolResult.fail("HTTP ${resp.code} ${resp.message}")
                    }
                    val html = resp.body?.string().orEmpty()
                    val text = HtmlExtractor.extractText(html)
                    if (text.isBlank()) {
                        return@withContext ToolResult.fail("未能提取到正文内容，页面可能是纯 JS 渲染")
                    }
                    val truncated = text.length > MAX_RESPONSE_CHARS
                    val shown = if (truncated) text.take(MAX_RESPONSE_CHARS) else text
                    ToolResult.ok(
                        "来源：$url\n长度：${text.length} 字符" +
                            if (truncated) "（已截断）" else "" +
                            "\n---\n$shown"
                    )
                }
            }
        } catch (e: Exception) {
            ToolResult.fail("读取失败：${e.message ?: e.javaClass.simpleName}\nURL: $url")
        }
    }
}

/**
 * 简易 HTML 正文提取
 *
 * 不引入 jsoup，用正则做去标签 + 去噪，
 * 对大多数静态页面足够用。
 */
object HtmlExtractor {

    private val scriptStyle = Regex(
        "<(script|style|noscript|iframe|svg)[^>]*>.*?</\\1>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val comment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val blockTag = Regex(
        "</?(p|div|br|li|tr|h[1-6]|section|article|header|footer|table|ul|ol|blockquote)[^>]*>",
        RegexOption.IGNORE_CASE
    )
    private val anyTag = Regex("<[^>]+>")
    private val multiBlank = Regex("[ \\t\\u00A0]+")
    private val multiNewline = Regex("\n{3,}")

    fun extractText(html: String): String {
        var s = html
        s = comment.replace(s, " ")
        s = scriptStyle.replace(s, " ")
        // 块级标签转成换行，保留段落结构
        s = blockTag.replace(s, "\n")
        s = anyTag.replace(s, "")
        // 常见实体解码
        s = s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
        s = multiBlank.replace(s, " ")
        s = multiNewline.replace(s, "\n\n")
        return s.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()
    }
}