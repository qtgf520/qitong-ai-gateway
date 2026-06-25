package com.qtwl.gateway.data.db

import com.qtwl.gateway.data.model.AiModel
import com.qtwl.gateway.data.model.ChatMessage
import com.qtwl.gateway.data.model.Conversation
import com.qtwl.gateway.data.model.Provider
import com.qtwl.gateway.data.model.TokenUsage
import com.qtwl.gateway.service.GatewayForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 备份管理器 —— 负责数据的 JSON 序列化与反序列化
 */
class BackupManager(private val database: AppDatabase) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 导出所有数据为 JSON 字符串
     */
    suspend fun exportToJson(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val providers = database.providerDao().getAllProvidersOnce()
            val models = database.aiModelDao().getAllModelsOnce()
            val conversations = database.conversationDao().getAllConversationsOnce()
            val messages = database.chatMessageDao().getAllMessagesOnce()
            val tokenUsage = database.tokenUsageDao().getAllUsageOnce()

            // ★ 导出配置：代理列表 + 网关端口
            val proxyListJson = GatewayForegroundService.getProxyListJson()
            val gatewayPort = GatewayForegroundService.getGatewayPort()

            val backupData = BackupData(
                providers = providers,
                models = models,
                conversations = conversations,
                messages = messages,
                tokenUsage = tokenUsage,
                proxyListJson = proxyListJson,
                gatewayPort = gatewayPort
            )

            val jsonString = json.encodeToString(BackupData.serializer(), backupData)
            Result.success(jsonString)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 从 JSON 字符串导入数据
     * 先清空所有旧数据，批量插入新数据
     */
    suspend fun importFromJson(jsonString: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val backupData = json.decodeFromString(BackupData.serializer(), jsonString)

            // 清除所有旧数据（按外键依赖顺序）
            database.tokenUsageDao().clearAll()
            database.chatMessageDao().deleteAll()
            database.conversationDao().deleteAll()
            database.aiModelDao().deleteAll()
            database.providerDao().deleteAll()

            // 批量插入新数据
            if (backupData.providers.isNotEmpty()) {
                database.providerDao().insertAll(backupData.providers)
            }
            if (backupData.models.isNotEmpty()) {
                database.aiModelDao().insertAll(backupData.models)
            }
            if (backupData.conversations.isNotEmpty()) {
                database.conversationDao().insertAll(backupData.conversations)
            }
            if (backupData.messages.isNotEmpty()) {
                database.chatMessageDao().insertAll(backupData.messages)
            }
            if (backupData.tokenUsage.isNotEmpty()) {
                database.tokenUsageDao().insertAll(backupData.tokenUsage)
            }

            // ★ 恢复配置：代理列表 + 网关端口
            if (backupData.proxyListJson.isNotBlank()) {
                GatewayForegroundService.saveProxyListJson(backupData.proxyListJson)
            }
            if (backupData.gatewayPort != 8889) {
                GatewayForegroundService.saveGatewayPort(backupData.gatewayPort)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
