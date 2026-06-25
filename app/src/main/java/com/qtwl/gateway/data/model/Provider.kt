package com.qtwl.gateway.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "providers")
@Serializable
data class Provider(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "type")
    val type: String,          // "OpenAI Compatible" / "Ollama" / "Custom"
    @ColumnInfo(name = "base_url")
    val baseUrl: String,
    @ColumnInfo(name = "port")
    val port: String = "",
    @ColumnInfo(name = "api_key")
    val apiKey: String? = null,
    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,
    @ColumnInfo(name = "order_index")
    val orderIndex: Int = 0
) {
    /**
     * 解析合并端口后的完整 Base URL
     * 如果 port 非空且 baseUrl 中尚未包含端口号，则自动拼接到 URL 中
     * 例如: baseUrl="http://localhost" + port="11434" → "http://localhost:11434"
     */
    val resolvedBaseUrl: String
        get() {
            if (port.isBlank()) return baseUrl
            // 检查 baseUrl 是否已包含端口号（如 http://localhost:11434）
            val portPattern = Regex("://[^:]+:\\d+")
            if (portPattern.containsMatchIn(baseUrl)) return baseUrl
            // 拼接端口
            return baseUrl.trimEnd('/') + ":" + port.trim()
        }
}