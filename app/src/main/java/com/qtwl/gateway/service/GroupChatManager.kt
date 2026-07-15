package com.qtwl.gateway.service

import android.content.Context
import android.content.SharedPreferences
import com.qtwl.gateway.GatewayApplication
import com.qtwl.gateway.data.db.AppDatabase
import com.qtwl.gateway.data.model.AiModel
import com.qtwl.gateway.utils.localizedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 群聊模式（虚拟沙箱 + AI互聊 + 总结）
 * - 支持 N 个参与模型 + 1 个总结者（可选）
 * - 用户消息 → 参与者并发回复 → 总结者生成结论
 * - AI 之间用 [@模型名称] 互相引用
 */
object GroupChatManager {

    private const val PREFS = "gateway_config"
    private const val KEY_GROUP_CHAT_ENABLED = "group_chat_enabled"
    private const val KEY_GROUP_CHAT_MODELS = "group_chat_models"
    private const val KEY_GROUP_CHAT_SUMMARIZER = "group_chat_summarizer"
    private const val KEY_GROUP_CHAT_MAX_ROUND = "group_chat_max_rounds"

    private val DEFAULT_CT = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // ============ 配置 ============

    private fun prefs(): SharedPreferences =
        GatewayApplication.getInstance().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean {
        return prefs().getBoolean(KEY_GROUP_CHAT_ENABLED, false)
    }
    fun setEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_GROUP_CHAT_ENABLED, enabled).apply()
    }

    fun getParticipantModels(): List<String> {
        val raw = prefs().getString(KEY_GROUP_CHAT_MODELS, "") ?: ""
        return raw.split(",").filter { it.isNotBlank() }.map { it.trim() }
    }

    fun setParticipantModels(models: List<String>) {
        prefs().edit().putString(KEY_GROUP_CHAT_MODELS, models.joinToString(",")).apply()
    }

    fun getSummarizerModel(): String = prefs().getString(KEY_GROUP_CHAT_SUMMARIZER, "") ?: ""
    fun setSummarizerModel(modelId: String) = prefs().edit().putString(KEY_GROUP_CHAT_SUMMARIZER, modelId).apply()

    fun getMaxRounds(): Int = prefs().getInt(KEY_GROUP_CHAT_MAX_ROUND, 2)
    fun setMaxRounds(rounds: Int) = prefs().edit().putInt(KEY_GROUP_CHAT_MAX_ROUND, rounds.coerceIn(1, 8)).apply()

    // ============ 核心逻辑 ============

    suspend fun executeGroupChat(
        database: AppDatabase,
        userMsg: String,
    ): String = withContext(Dispatchers.IO) {
        val participantIds = getParticipantModels()
        if (participantIds.isEmpty()) {
            return@withContext localizedText("群聊模式已开启，但未选择参与模型。请在管理页配置。", "Group chat is enabled, but no participant models are selected. Configure them on the management page.")
        }

        val allModels = database.aiModelDao().getEnabledModelsList().filter { it.isEnabled }
        val summarizerId = getSummarizerModel()
        val maxRounds = getMaxRounds()

        val fullLog = mutableListOf<String>().apply {
            add(localizedText("📋 **群聊开始**", "📋 **Group chat started**"))
            add(localizedText("用户提问：", "User question: ") + userMsg)
            add("---")
        }

        val semaphore = Semaphore(3) // 最多3个并发

        // ===== 每轮 =====
        for (round in 1..maxRounds) {
            fullLog.add("")
            fullLog.add(localizedText("## 第 $round 轮", "## Round $round"))

            val futures = participantIds.mapNotNull { modelId ->
                val model = allModels.firstOrNull { it.modelId == modelId } ?: return@mapNotNull null
                val provider = database.providerDao().getProviderById(model.providerId) ?: return@mapNotNull null
                if (!provider.isEnabled) return@mapNotNull null

                launch {
                    semaphore.withPermit {
                        try {
                            val bodyJson = buildGroupTurnJson(
                                model.modelId, model.displayName, userMsg,
                                fullLog.joinToString("\n"), round == 1
                            )
                            val (reply, _) = callUpstream(provider, bodyJson.toString())
                            if (reply.isNotBlank()) {
                                fullLog.add("\n**${model.displayName}**：$reply")
                            }
                        } catch (e: Exception) {
                            fullLog.add("\n**${model.displayName}**: " + localizedText("（请求失败: ", "(request failed: ") + (e.message?.take(50) ?: "unknown") + ")")
                        }
                    }
                }
            }

            futures.forEach { it.join() }
        }

        // ===== 总结者 =====
        if (summarizerId.isNotBlank()) {
            val sumModel = allModels.firstOrNull { it.modelId == summarizerId }
            val sumProvider = sumModel?.let { database.providerDao().getProviderById(it.providerId) }
            if (sumModel != null && sumProvider != null && sumProvider.isEnabled) {
                try {
                    val summaryPrompt = buildSummationPrompt(fullLog.joinToString("\n"))
                    val summaryBody = buildJsonObject {
                        put("model", JsonPrimitive(sumModel.modelId))
                        put("messages", buildJsonArray {
                            add(buildJsonObject { put("role", JsonPrimitive("system")); put("content", JsonPrimitive(localizedText("你是专业的群聊总结者。输出结构化总结。", "You are a professional group-chat summarizer. Produce a structured summary."))) })
                            add(buildJsonObject { put("role", JsonPrimitive("user")); put("content", JsonPrimitive(summaryPrompt)) })
                        })
                        put("max_tokens", JsonPrimitive(600))
                        put("temperature", JsonPrimitive(0.5))
                    }.toString()
                    val (summary, _) = callUpstream(sumProvider, summaryBody)
                    if (summary.isNotBlank()) {
                        fullLog.add("")
                        fullLog.add("---")
                        fullLog.add(localizedText("## 📝 总结报告", "## 📝 Summary report"))
                        fullLog.add(summary)
                    }
                } catch (e: Exception) {
                    fullLog.add("")
                    fullLog.add("---")
                    fullLog.add(localizedText("## 📝 总结失败: ", "## 📝 Summary failed: ") + (e.message?.take(100) ?: "unknown"))
                }
            }
        }

        fullLog.joinToString("\n")
    }

    // ============ 内部构造 ============

    private fun buildGroupTurnJson(
        modelId: String, modelName: String, userMsg: String,
        history: String, isFirstRound: Boolean
    ): JsonObject = buildJsonObject {
        val systemMsg = buildString {
            append(localizedText("你是 $modelName，正在参与一个AI群聊讨论。\n", "You are $modelName, participating in an AI group-chat discussion.\n"))
            append(localizedText("用户的问题：", "User question: ")).append(userMsg).append('\n')
            append(localizedText("当前讨论记录：\n", "Current discussion log:\n"))
            append(history.takeLast(4000))
            if (isFirstRound) append(localizedText("\n请首先发表你的看法。如需点名，用 [@模型名称] 格式。", "\nGive your view first. To address another model, use [@model name]."))
            else append(localizedText("\n结合以上讨论，提出你的补充、反驳或新观点。使用 [@模型名称] 点名。", "\nBased on the discussion, add, challenge, or introduce a new point. Use [@model name] to address another model."))
        }

        put("model", JsonPrimitive(modelId))
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", JsonPrimitive("system")); put("content", JsonPrimitive(localizedText("你是一个专业的AI助手，正在参与群聊讨论。输出简洁专业。", "You are a professional AI assistant participating in a group chat. Be concise and professional."))) })
            add(buildJsonObject { put("role", JsonPrimitive("user")); put("content", JsonPrimitive(systemMsg)) })
        })
        put("max_tokens", JsonPrimitive(400))
        put("temperature", JsonPrimitive(0.7))
    }

    private fun buildSummationPrompt(fullLog: String): String = buildString {
        appendLine(localizedText("请阅读以下AI群聊讨论记录，输出一份简洁的总结报告：", "Read the following AI group-chat log and produce a concise summary report:"))
        appendLine()
        appendLine(fullLog.takeLast(5000))
        appendLine()
        appendLine(localizedText("总结要求：", "Summary requirements:"))
        appendLine(localizedText("1. 用户的核心问题是什么", "1. Identify the user's core question"))
        appendLine(localizedText("2. 各位AI的主要观点", "2. Summarize each AI's main points"))
        appendLine(localizedText("3. 存在的分歧点", "3. Identify disagreements"))
        appendLine(localizedText("4. 最终的综合结论", "4. Give a final synthesis"))
        appendLine()
        appendLine(localizedText("请用结构化格式输出。", "Use a structured format."))
    }

    // ============ 上游调用 ============

    private suspend fun callUpstream(
        provider: com.qtwl.gateway.data.model.Provider,
        bodyJson: String
    ): Pair<String, String> {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(60000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        val url = (provider.chatPath ?: "/v1/chat/completions").run {
            if (startsWith("/")) this else "/$this"
        }
        val resolvedUrl = "${provider.resolvedBaseUrl.trimEnd('/')}${url}"

        val req = okhttp3.Request.Builder()
            .url(resolvedUrl)
            .header("Authorization", "Bearer ${provider.apiKey}")
            .header("Content-Type", "application/json")
            .post(bodyJson.toByteArray().toRequestBody(DEFAULT_CT))
            .build()

        val resp = client.newCall(req).execute()
        val bodyStr = resp.body?.string() ?: "{}"
        resp.close()

        val content: String = try {
            val root = json.parseToJsonElement(bodyStr).jsonObject
            choicesContentOrNull(root) ?: root["content"]?.jsonPrimitive?.content ?: ""
        } catch (e: Exception) {
            println("GroupChat JSON解析失败, status=${resp.code}, body=${bodyStr.take(200)}, error=${e.message}")
            ""
        }

        return content to bodyStr
    }

    private fun choicesContentOrNull(obj: JsonObject): String? {
        val choices = obj["choices"]?.jsonArray ?: return null
        for (choiceRaw in choices) {
            try {
                val choice = choiceRaw.jsonObject
                // method 1: choices[].message.content
                choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content?.let { return it }
                // method 2: choices[].delta.content (streaming)
                choice["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.content?.let { return it }
            } catch (e: Exception) {
                // skip malformed choice
            }
        }
        return null
    }
}