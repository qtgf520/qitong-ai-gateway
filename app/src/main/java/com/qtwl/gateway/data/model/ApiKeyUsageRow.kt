package com.qtwl.gateway.data.model

/**
 * 按API密钥分组的用量统计行 —— DAO查询返回
 */
data class ApiKeyUsageRow(
    val apiKeyLabel: String,
    val total: Long,
    val prompt: Long,
    val completion: Long,
    val upload: Long,
    val download: Long,
    val calls: Int
)
