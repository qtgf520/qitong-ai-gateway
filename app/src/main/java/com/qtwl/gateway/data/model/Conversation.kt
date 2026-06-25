package com.qtwl.gateway.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 对话会话实体 —— 组织聊天消息
 */
@Entity(tableName = "conversations")
@Serializable
data class Conversation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "title")
    val title: String = "新对话",
    @ColumnInfo(name = "model_id")
    val modelId: String? = null,
    @ColumnInfo(name = "provider_id")
    val providerId: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "message_count")
    val messageCount: Int = 0,
    @ColumnInfo(name = "total_tokens")
    val totalTokens: Int = 0
)