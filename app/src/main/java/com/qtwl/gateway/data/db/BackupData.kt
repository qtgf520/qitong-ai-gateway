package com.qtwl.gateway.data.db

import com.qtwl.gateway.data.model.AiModel
import com.qtwl.gateway.data.model.ChatMessage
import com.qtwl.gateway.data.model.Conversation
import com.qtwl.gateway.data.model.Provider
import com.qtwl.gateway.data.model.TokenUsage
import com.qtwl.gateway.service.GatewayForegroundService
import kotlinx.serialization.Serializable

/**
 * 备份数据的顶层结构，包含数据库中所有的表数据
 * 序列化为 JSON 文件供导出/导入使用
 */
@Serializable
data class BackupData(
    val version: Int = 2,
    val timestamp: Long = System.currentTimeMillis(),
    val providers: List<Provider> = emptyList(),
    val models: List<AiModel> = emptyList(),
    val conversations: List<Conversation> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val tokenUsage: List<TokenUsage> = emptyList(),
    val proxyListJson: String = "",           // ★ 新增：代理列表配置
    val gatewayPort: Int = 8889                // ★ 新增：网关端口
)
