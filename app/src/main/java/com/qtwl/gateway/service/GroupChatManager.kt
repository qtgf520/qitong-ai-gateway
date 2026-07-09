package com.qtwl.gateway.service

import com.qtwl.gateway.GatewayApplication
import com.qtwl.gateway.data.db.AppDatabase
import com.qtwl.gateway.data.model.AiModel
import com.qtwl.gateway.gateway.GatewayScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * 群聊模式（虚拟沙箱 + AI互聊 + 总结）
 *
 * 架构：
 * 用户选择 N 个 AI 模型 + 1 个总结模型 → 开启群聊
 * 用户发消息 → AI-A → AI-B → AI-C ... → 总结者输出最终结论
 *
 * 每个 AI 能看到完整对话历史，AI 之间用 [@AI名称] 互相引用
 */
object GroupChatManager {

    private const val PREFS = "gateway_config"
    private const val KEY_GROUP_CHAT_ENABLED = "group_chat_enabled"
    private const val KEY_GROUP_CHAT_MODELS = "group_chat_models"   // JSON array of model IDs
    private const val KEY_GROUP_CHAT_SUMMARIZER = "group_chat_summarizer" // model ID for summarizer
    private const val KEY_GROUP_CHAT_MAX_ROUNDS = "group_chat_max_rounds"

    private val DEFAULT_CT = "application/json".toMediaType()
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = false }

    // 当前群聊会话（运行时）
    data class GroupChatSession(
        val messages: MutableList<String> = mutableListOf(),     // 完整对话记录
        val participants: List<String> = emptyList(),             // 参与模型ID列表
        val currentRound: Int = 0,
        val maxRounds: Int = 2
    )

    private var currentSession: GroupChatSession? = null

    private fun prefs(): SharedPreferences =
        GatewayApplication.getInstance().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ==================== 配置 ====================

    /** 群聊是否开启 */
    fun isEnabled(): Boolean = prefs().getBoolean(KEY_GROUP_CHAT_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_GROUP_CHAT_ENABLED, enabled).apply()
        if (!enabled) currentSession = null
    }

    /** 获取参与群聊的模型ID列表 */
    fun getParticipantModels(): List<String> {
        val raw = prefs().getString(KEY_GROUP_CHAT_MODELS, "[]") ?: "[]"
        return try {
            json.decodeFromString<kotlinx.serialization.json.JsonArray>(raw).map { it.jsonPrimitive.content }
        } catch (_: Exception) { emptyList() }
    }

    /** 设置参与群聊的模型ID列表 */
    fun setParticipantModels(models: List<String>) {
        val arr = json.encodeToString(JsonArray(models.map { JsonPrimitive(it) }))
        prefs().edit().putString(KEY_GROUP_CHAT_MODELS, arr).apply()
    }

    /** 获取总结模型ID */
    fun getSummarizerModel(): String = prefs().getString(KEY_GROUP_CHAT_SUMMARIZER, "") ?: ""

    fun setSummarizerModel(modelId: String) {
        prefs().edit().putString(KEY_GROUP_CHAT_SUMMARIZER, modelId).apply()
    }

    /** 获取最大轮次 */
    fun getMaxRounds(): Int = prefs().getInt(KEY_GROUP_CHAT_MAX_ROUNDS, 2)

    fun setMaxRounds(rounds: Int) {
        prefs().edit().putInt(KEY_GROUP_CHAT_MAX_ROUNDS, rounds).apply()
    }

    // ==================== 运行逻辑 ====================

    /**
     * 执行群聊：用户消息 → AI依次回复 → 总结者总结
     *
     * @return 返回最终拼接的群聊结果文本
     */
    suspend fun executeGroupChat(
        database: AppDatabase,
        userMsg: String,
        brainModel: AiModel,
        brainProvider: com.qtwl.gateway.data.model.Provider
    ): String = withContext(Dispatchers.IO) {
        val participants = getParticipantModels()
        if (participants.isEmpty()) {
            return@withContext "群聊模式已开启，但未选择参与模型。请在管理页配置。"
        }

        val allModels = database.aiModelDao().getEnabledModelsList().filter { it.isEnabled }
        val summarizerId = getSummarizerModel()
        val maxRounds = getMaxRounds()

        val sb = StringBuilder()
        sb.appendLine("📋 **群聊开始**")
        sb.appendLine("用户提问：$userMsg")
        sb.appendLine("---")

        // 每轮按参与者顺序发言
        for (round in 1..maxRounds) {
            sb.appendLine("\n## 第 $round 轮")
            for (modelId in participants) {
                val model = allModels.find { it.modelId == modelId } ?: continue
                val provider = database.providerDao().getProviderById(model.providerId) ?: continue
                if (!provider.isEnabled) continue

                // 构造 prompt：携带完整对话历史
                val history = sb.toString().take(3000) // 截断避免超长
                val prompt = """你是 ${model.displayName}，正在参与一个AI群聊讨论。
用户的问题：$userMsg

当前讨论记录：
$history

请根据以上讨论，${if (round == 1) "首先发表你的看法" else "结合前面的AI发言，提出你的补充、反驳或新观点"}。
如果你同意或不同意某位AI的观点，请用 [@模型名称] 格式点名。
保持简洁专业，200字以内。"""

                val body = """{"model":"${model.modelId}","messages":[{"role":"system","content":"你是一个专业的AI助手，正在参与群聊讨论。"},{"role":"user","content":${json.encodeToString(JsonPrimitive(prompt))}}],"max_tokens":300,"temperature":0.7}"""

                try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(15000, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .readTimeout(30000, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .build()
                    val req = okhttp3.Request.Builder()
                        .url("${provider.resolvedBaseUrl.trimEnd('/')}/v1/chat/completions")
                        .post(body.toByteArray().toRequestBody(DEFAULT_CT))
                        .apply { if (!provider.apiKey.isNullOrBlank()) header("Authorization", "Bearer ${provider.apiKey}") }
                        .build()
                    val resp = client.newCall(req).execute()
                    val respBody = resp.body?.string() ?: "{}"
                    resp.close()

                    val reply = try {
                        json.parseToJsonElement(respBody).jsonObject
                            .get("choices")?.jsonArray?.firstOrNull()?.jsonObject
                            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content ?: ""
                    } catch (_: Exception) { "" }

                    if (reply.isNotBlank()) {
                        sb.appendLine("\n**${model.displayName}**：$reply")
                    }
                } catch (e: Exception) {
                    sb.appendLine("\n**${model.displayName}**：（请求失败: ${e.message?.take(50)}）")
                }
            }
        }

        // === 总结 ===
        if (summarizerId.isNotBlank()) {
            val summarizerModel = allModels.find { it.modelId == summarizerId }
            val summarizerProvider = if (summarizerModel != null) database.providerDao().getProviderById(summarizerModel.providerId) else null

            if (summarizerModel != null && summarizerProvider != null && summarizerProvider.isEnabled) {
                val fullHistory = sb.toString().take(4000)
                val summaryPrompt = """你是总结者，请阅读以下AI群聊讨论记录，输出一份简洁的总结报告：

$fullHistory

总结要求：
1. 用户的核心问题是什么
2. 各位AI的主要观点
3. 存在的分歧点
4. 最终的综合结论

请用结构化格式输出。"""
                val summaryBody = """{"model":"${summarizerModel.modelId}","messages":[{"role":"system","content":"你是专业的群聊总结者。"},{"role":"user","content":${json.encodeToString(JsonPrimitive(summaryPrompt))}}],"max_tokens":500,"temperature":0.5}"""

                try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(15000, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .readTimeout(60000, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .build()
                    val req = okhttp3.Request.Builder()
                        .url("${summarizerProvider.resolvedBaseUrl.trimEnd('/')}/v1/chat/completions")
                        .post(summaryBody.toByteArray().toRequestBody(DEFAULT_CT))
                        .apply { if (!summarizerProvider.apiKey.isNullOrBlank()) header("Authorization", "Bearer ${summarizerProvider.apiKey}") }
                        .build()
                    val resp = client.newCall(req).execute()
                    val respBody = resp.body?.string() ?: "{}"
                    resp.close()

                    val summary = try {
                        json.parseToJsonElement(respBody).jsonObject
                            .get("choices")?.jsonArray?.firstOrNull()?.jsonObject
                            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content ?: ""
                    } catch (_: Exception) { "" }

                    if (summary.isNotBlank()) {
                        sb.appendLine("\n\n---\n## 📝 总结报告\n$summary")
                    }
                } catch (e: Exception) {
                    sb.appendLine("\n\n---\n## ⚠️ 总结失败: ${e.message?.take(50)}")
                }
            }
        }

        sb.toString()
    }
}