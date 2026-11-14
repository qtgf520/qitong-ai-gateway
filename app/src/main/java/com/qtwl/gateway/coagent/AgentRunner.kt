package com.qtwl.gateway.coagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext

/**
 * 全新的 Agent 执行器。
 *
 * 与旧实现（agent/AgentExecutor.kt）没有任何共用逻辑，从零写：
 *   · 请求体最小化，可选参数被拒时自动摘掉重试（见 Wire）
 *   · 工具 schema 由声明生成，不会出现非法结构
 *   · 取消是真正的取消：每一轮都检查协程状态，CancellationException 原样抛出
 *   · 错误信息包含端点、已发送参数、服务商原文
 *   · 思考开关（ThinkingLevel）与思考内容回调
 */
class AgentRunner(
    private val ep: Endpoint,
    private val modelId: String,
    private val systemPrompt: String,
    private val tools: List<AgentToolSpec> = Toolkit.all(),
    private val thinking: ThinkingLevel = ThinkingLevel.OFF,
    private val streaming: Boolean = true,
    private val maxRounds: Int = 12,
    private val contextTokens: Int = 128_000,
    private val listener: Listener = object : Listener {}
) {

    /** 事件回调。默认空实现，调用方只覆写关心的几个。 */
    interface Listener {
        fun onRound(round: Int) {}
        fun onStatus(text: String) {}
        fun onText(delta: String) {}
        fun onReasoning(delta: String) {}
        fun onToolStart(name: String, args: String) {}
        fun onToolEnd(name: String, ok: Boolean, result: String) {}
    }

    data class Outcome(
        val ok: Boolean,
        val answer: String,
        val reasoning: String = "",
        val rounds: Int = 0,
        val toolCount: Int = 0,
        val error: String? = null,
        val hitLimit: Boolean = false
    )

    suspend fun run(
        userText: String,
        history: List<Pair<String, String>> = emptyList()
    ): Outcome = withContext(Dispatchers.IO) {

        val msgs = mutableListOf<JsonObject>()
        msgs += message("system", systemPrompt)
        history.takeLast(40).forEach { pair ->
            if (pair.second.isNotBlank()) msgs += message(pair.first, pair.second)
        }
        msgs += message("user", userText)

        val wire = Wire(ep)
        val schemas: JsonArray? = if (tools.isEmpty()) null else buildJsonArray {
            tools.forEach { add(it.schema()) }
        }

        val finalAnswer = StringBuilder()
        val allReasoning = StringBuilder()
        var rounds = 0
        var toolCount = 0

        while (rounds < maxRounds) {
            coroutineContext.ensureActive()
            rounds++
            listener.onRound(rounds)
            listener.onStatus("第 " + rounds + " 轮 · 正在推理")

            // ★ 单轮请求失败不再直接结束整次运行：
            //   网络抖动重试 2 次；仍然失败时把已经产出的内容保留下来，
            //   并把原因附在后面 —— 会话不会因为一次失败就断掉。
            var turn: ChatTurn? = null
            var lastError: Throwable? = null
            var attempt = 0
            while (turn == null && attempt < 3) {
                attempt++
                try {
                    turn = wire.chat(
                        model = modelId,
                        messages = sanitized(msgs),
                        tools = schemas,
                        stream = streaming,
                        thinking = thinking,
                        onDelta = { d -> listener.onText(d) },
                        onReasoning = { d ->
                            allReasoning.append(d)
                            listener.onReasoning(d)
                        },
                        onRetry = { why -> listener.onStatus(why) }
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    // 400/401 之类已由 Wire 尽力修过，再重试也没意义
                    lastError = e
                    break
                } catch (e: Throwable) {
                    lastError = e
                    if (attempt < 3) {
                        listener.onStatus("网络波动，正在重试（" + attempt + "/2）")
                        kotlinx.coroutines.delay(700L * attempt)
                    }
                }
            }

            val current = turn
            if (current == null) {
                val partial = finalAnswer.toString()
                val errText = readableError(lastError ?: IllegalStateException("未知错误"))
                return@withContext Outcome(
                    ok = partial.isNotBlank(),
                    answer = if (partial.isBlank()) errText else partial,
                    reasoning = allReasoning.toString(),
                    rounds = rounds,
                    toolCount = toolCount,
                    error = if (partial.isBlank()) errText else null
                )
            }

            if (current.toolCalls.isEmpty()) {
                finalAnswer.append(current.text)
                listener.onStatus("完成（" + rounds + " 轮）")
                return@withContext Outcome(
                    ok = true,
                    answer = finalAnswer.toString(),
                    reasoning = allReasoning.toString(),
                    rounds = rounds,
                    toolCount = toolCount
                )
            }

            // 记录 assistant 的工具调用，供下一轮上下文使用
            // 上游偶尔不给 id，这里补一个稳定的，保证 tool_calls 与 tool 消息能对上
            val callIds = current.toolCalls.mapIndexed { idx, c ->
                if (c.id.isBlank()) "call_" + rounds + "_" + idx else c.id
            }
            msgs += buildJsonObject {
                put("role", "assistant")
                put("content", current.text)
                put("tool_calls", buildJsonArray {
                    current.toolCalls.forEachIndexed { idx, call ->
                        add(buildJsonObject {
                            put("id", callIds[idx])
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", call.name)
                                put("arguments", call.argsJson.ifBlank { "{}" })
                            })
                        })
                    }
                })
            }

            current.toolCalls.forEachIndexed { idx, call ->
                coroutineContext.ensureActive()
                toolCount++
                listener.onToolStart(call.name, call.argsJson)
                val spec = tools.firstOrNull { it.name == call.name }
                val result = if (spec == null) {
                    "未知工具 " + call.name + "。可用工具：" + tools.joinToString(", ") { it.name }
                } else {
                    try {
                        spec.run(call.args())
                    } catch (e: Throwable) {
                        "工具执行异常：" + (e.message ?: e.javaClass.simpleName)
                    }
                }
                listener.onToolEnd(call.name, spec != null, result)
                msgs += buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", callIds[idx])
                    put("content", result.take(20000))
                }
            }

            trimIfNeeded(msgs)
        }

        listener.onStatus("已达轮次上限")
        Outcome(
            ok = finalAnswer.isNotEmpty(),
            answer = finalAnswer.toString(),
            reasoning = allReasoning.toString(),
            rounds = rounds,
            toolCount = toolCount,
            hitLimit = true,
            error = if (finalAnswer.isEmpty()) "达到最大轮次（" + maxRounds + "）仍未得到回答" else null
        )
    }

    private fun roleOf(m: JsonObject): String =
        m["role"]?.toString()?.removeSurrounding("\"") ?: ""

    private fun idOf(m: JsonObject): String? =
        m["tool_call_id"]?.toString()?.removeSurrounding("\"")

    private fun toolCallIds(m: JsonObject): List<String> {
        val arr = m["tool_calls"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val obj = e as? JsonObject ?: return@mapNotNull null
            obj["id"]?.toString()?.removeSurrounding("\"")
        }
    }

    /**
     * 发出去之前把消息序列修好。
     *
     * 上游（尤其 DeepSeek 官方）要求非常死：
     *   · role=tool 必须紧跟在带 tool_calls 的 assistant 后面
     *   · assistant 的每个 tool_call 都必须有对应的 tool 回应
     * 上下文裁剪可能把配对拆散，所以每轮都体检一次：
     * 配不全的 tool_calls 降级成纯文本，没有归属的 tool 消息直接丢掉。
     */
    private fun sanitized(input: List<JsonObject>): List<JsonObject> {
        val out = ArrayList<JsonObject>(input.size)
        var i = 0
        while (i < input.size) {
            val m = input[i]
            val role = roleOf(m)
            if (role == "tool") {
                i++   // 孤儿 tool 消息，丢弃
                continue
            }
            val ids = toolCallIds(m)
            if (role == "assistant" && ids.isNotEmpty()) {
                var j = i + 1
                val answered = LinkedHashMap<String, JsonObject>()
                while (j < input.size && roleOf(input[j]) == "tool") {
                    val id = idOf(input[j])
                    if (id != null && ids.contains(id)) answered[id] = input[j]
                    j++
                }
                if (answered.size == ids.size) {
                    out.add(m)
                    ids.forEach { id -> answered[id]?.let { out.add(it) } }
                } else {
                    // 半截的工具调用：降级成纯文本，别让上游 400
                    out.add(JsonObject(m - "tool_calls"))
                }
                i = j
                continue
            }
            out.add(m)
            i++
        }
        return out
    }

    private fun message(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    /** 粗略 token 估算：中文约 1 字 1 token，英文约 4 字符 1 token */
    private fun estimate(messages: List<JsonObject>): Int {
        var total = 0
        messages.forEach { m ->
            val text = m.toString()
            val cjk = text.count { it.code in 0x4E00..0x9FFF }
            val other = text.length - cjk
            total += cjk + other / 3
        }
        return total
    }

    /** 超长时丢掉中间最老的消息，保留 system 与最近几轮 */
    private fun trimIfNeeded(msgs: MutableList<JsonObject>) {
        if (contextTokens <= 0) return
        val limit = (contextTokens * 0.75).toInt()
        var safety = 0
        while (estimate(msgs) > limit && msgs.size > 6 && safety < 50) {
            safety++
            // 第一个是 system，永远保留；从索引 1 开始丢
            msgs.removeAt(1)
        }
    }

    private fun readableError(e: Throwable): String = when (e) {
        is ApiException -> e.message ?: "请求失败"
        is SocketTimeoutException -> "请求超时：模型没有在设定时间内返回。可在 Agent 设置里加大超时。\n端点：" + ep.host
        is UnknownHostException -> "DNS 解析失败：" + ep.host + "\n检查网络或代理设置。"
        is SocketException -> "连接被中断（" + e.message + "）\n端点：" + ep.host +
            "　代理：" + (if (ep.useProxy) "已开启" else "直连") +
            "\n排查：① 关掉该模型的代理重试 ② 换官方端点 ③ 服务商可能掐断了长连接"
        is IOException -> "网络错误：" + (e.message ?: e.javaClass.simpleName) + "\n端点：" + ep.host
        else -> e.message ?: e.javaClass.simpleName
    }
}
