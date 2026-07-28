package com.qtwl.gateway.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Token用量统计实体 —— 记录每次API调用的消耗
 * 对标 One-API 的额度管理
 */
@Entity(
    tableName = "token_usage",
    foreignKeys = [
        ForeignKey(
            entity = Provider::class,
            parentColumns = ["id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("provider_id"), Index("timestamp")]
)
@Serializable
data class TokenUsage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "provider_id")
    val providerId: Long,
    @ColumnInfo(name = "model_id")
    val modelId: String,
    @ColumnInfo(name = "prompt_tokens")
    val promptTokens: Int = 0,
    @ColumnInfo(name = "completion_tokens")
    val completionTokens: Int = 0,
    @ColumnInfo(name = "total_tokens")
    val totalTokens: Int = 0,
    @ColumnInfo(name = "upload_bytes")
    val uploadBytes: Long = 0,
    @ColumnInfo(name = "download_bytes")
    val downloadBytes: Long = 0,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "api_key_label")
    val apiKeyLabel: String = ""  // 使用的API密钥标签（空=本地/无密钥）
)