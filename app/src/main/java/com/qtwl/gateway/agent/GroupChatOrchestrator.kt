package com.qtwl.gateway.agent

import com.qtwl.gateway.network.UpstreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 多 AI 群聊协作
 *
 * 两种模式：
 *  1. **互补模式**：两个 AI 各负责不同领域，先后回答，后者能看到前者内容
 *  2. **辩论模式**：两个 AI 互相审视、指出问题，若干轮后由主持 AI 总结
 */
class GroupChatOrchestrator(
    private val participants: List<ChatParticipant>,
    private val mode: GroupMode = GroupMode.Complement,
    private val totalRounds: Int = 2,
    private val onSpeakerStart: ((ChatParticipant, Int) -> Unit)? = null,
    private val onDelta: ((ChatParticipant, String) -> Unit)? = null,
    private val onSpeakerDone: ((ChatParticipant, String) -> Unit)? = null
) {

    companion object {
        private const val MAX_TOTAL_CHARS = 24_000
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 运行群聊
     *
     * @param userMessage 用户提问
     * @param history 已有对话
     * @return 所有发言记录
     */
    suspend fun run(
        userMessage: String,
        history: List<Pair<String, String>> = emptyList()
    ): GroupChatResult = withContext(Dispatchers.IO) {

        if (participants.isEmpty()) {
            return@withContext GroupChatResult(false, emptyList(), "没有配置参与者")
        }

        val transcript = mutableListOf<SpeakerMessage>()

        repeat(totalRounds) { roundIndex ->
            participants.forEach { speaker ->
                onSpeakerStart?.invoke(speaker, roundIndex + 1)

                val prompt = buildSpeakerPrompt(
                    speaker = speaker,
                    userMessage = userMessage,
                    history = history,
                    transcript = transcript,
                    roundIndex = roundIndex
                )

                val reply = try {
                    callModel(speaker, prompt) { delta ->
                        onDelta?.invoke(speaker, delta)
                    }
                } catch (e: Exception) {
                    onSpeakerDone?.invoke(speaker, "")
                    transcript += SpeakerMessage(
                        speaker = speaker.name,
                        content = "（${speaker.name} 调用失败：${e.message ?: "未知错误"}）",
                        round = roundIndex + 1,
                        failed = true
                    )
                    return@forEach
                }

                if (reply.isBlank()) {
                    onSpeakerDone?.invoke(speaker, "")
                    return@forEach
                }

                transcript += SpeakerMessage(
                    speaker = speaker.name,
                    content = reply,
                    round = roundIndex + 1
                )
                onSpeakerDone?.invoke(speaker, reply)
            }
        }

        // 如果有主持人，让它总结
        val host = participants.firstOrNull { it.isHost }
        val finalAnswer = if (host != null && transcript.size > 1) {
            try {
                summarize(host, userMessage, transcript)
            } catch (_: Exception) {
                transcript.lastOrNull()?.content.orEmpty()
            }
        } else {
            transcript.lastOrNull()?.content.orEmpty()
        }

        GroupChatResult(true, transcript, finalAnswer)
    }

    /**
     * 为某个发言者构造提示词
     *
     * 关键：把之前所有人的发言都塞进上下文，让它们能互相看见
     */
    private fun buildSpeakerPrompt(
        speaker: ChatParticipant,
        userMessage: String,
        history: List<Pair<String, String>>,
        transcript: List<SpeakerMessage>,
        roundIndex: Int
    ): String = buildString {
        appendLine(AppKnowledge.groupChatPrompt(speaker.role))
        appendLine()
        appendLine("## 你的身份")
        appendLine("你是 **${speaker.name}**，职责：${speaker.role}")
        appendLine()

        if (history.isNotEmpty()) {
            appendLine("## 之前的对话")
            history.takeLast(6).forEach { (role, content) ->
                val who = if (role == "user") "用户" else "AI"
                appendLine("$who：${content.take(300)}")
            }
            appendLine()
        }

        appendLine("## 用户的问题")
        appendLine(userMessage)
        appendLine()

        if (transcript.isNotEmpty()) {
            appendLine("## 其他人已经说过的话")
            var used = 0
            transcript.forEach { msg ->
                if (used > MAX_TOTAL_CHARS) return@forEach
                val block = "[${msg.speaker}] ${msg.content}\n"
                appendLine(block)
                used += block.length
            }
            appendLine()
        }

        appendLine("## 当前任务")
        when (mode) {
            GroupMode.Complement -> {
                appendLine("从你的职责出发，补充你能贡献的部分。")
                if (transcript.isNotEmpty()) {
                    appendLine("如果别人已经说清楚的，不要重复；只补充新的、或纠正错误的。")
                }
                appendLine("如果整体已经完整，简短说明你的认可即可。")
            }
            GroupMode.Debate -> {
                appendLine("审视上面的发言，指出其中的问题、遗漏或不准确之处。")
                appendLine("如果认同，也要说明理由；如果有更好的做法，直接给出。")
                appendLine("不要为了反对而反对。")
            }
        }
        if (roundIndex > 0) {
            appendLine("这是第 ${roundIndex + 1} 轮讨论，请聚焦在收敛结论上，不要继续发散。")
        }
        appendLine()
        appendLine("直接输出你的发言内容，不要加自己的名字前缀。")
    }

    /** 让主持人总结所有发言 */
    private fun summarize(
        host: ChatParticipant,
        userMessage: String,
        transcript: List<SpeakerMessage>
    ): String {
        val prompt = buildString {
            appendLine("你是本次讨论的主持人，负责给出最终结论。")
            appendLine()
            appendLine("## 用户的问题")
            appendLine(userMessage)
            appendLine()
            appendLine("## 各方的发言")
            transcript.forEach { msg ->
                appendLine("[${msg.speaker}] ${msg.content.take(1200)}")
                appendLine()
            }
            appendLine("## 任务")
            appendLine("综合以上讨论，给用户一份清晰、准确的最终回答。")
            appendLine("要求：")
            appendLine("1. 直接回答问题，不要复述讨论过程")
            appendLine("2. 如果各方有分歧，说明哪种做法更合适及原因")
            appendLine("3. 保留所有有价值的具体信息（数字、步骤、代码等）")
            appendLine("4. 语言简洁，条理清晰")
        }
        return callModel(host, prompt, null)
    }

    /** 调用单个参与者模型 */
    private fun callModel(
        participant: ChatParticipant,
        prompt: String,
        onDelta: ((String) -> Unit)?
    ): String {
        val payload = buildJsonObject {
            put("model", participant.modelId)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", AppKnowledge.groupChatPrompt(participant.role))
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("temperature", participant.temperature)
            put("stream", onDelta != null)
        }

        val url = participant.baseUrl.trimEnd('/') +
            (participant.chatPath ?: "/v1/chat/completions")

        val client = UpstreamClient.getDirectClient().newBuilder()
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${participant.apiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw IllegalStateException("HTTP ${resp.code}: ${err.take(200)}")
            }
            if (onDelta != null) {
                return SseParser.parse(resp, onDelta).content
            }
            val body = resp.body?.string().orEmpty()
            val obj = json.parseToJsonElement(body).jsonObject
            return obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                .orEmpty()
        }
    }
}

/** 群聊参与者 */
data class ChatParticipant(
    val name: String,
    val role: String,
    val baseUrl: String,
    val apiKey: String,
    val modelId: String,
    val chatPath: String? = null,
    val temperature: Double = 0.7,
    /** 是否为主持人（负责总结） */
    val isHost: Boolean = false
)

/** 群聊模式 */
enum class GroupMode(val label: String) {
    Complement("互补"),
    Debate("辩论")
}

/** 一条发言 */
data class SpeakerMessage(
    val speaker: String,
    val content: String,
    val round: Int = 1,
    val failed: Boolean = false
)

/** 群聊结果 */
data class GroupChatResult(
    val success: Boolean,
    val transcript: List<SpeakerMessage>,
    val finalAnswer: String
)