package com.qtwl.gateway.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 聊天消息实体 —— 本地保存对话历史
 */
@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversation_id")]
)
@Serializable
data class ChatMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "conversation_id")
    val conversationId: Long,
    @ColumnInfo(name = "role")
    val role: String,           // "user" / "assistant" / "system"
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "model_id")
    val modelId: String? = null,
    @ColumnInfo(name = "provider_id")
    val providerId: Long? = null,
    @ColumnInfo(name = "prompt_tokens")
    val promptTokens: Int = 0,
    @ColumnInfo(name = "completion_tokens")
    val completionTokens: Int = 0,
    @ColumnInfo(name = "total_tokens")
    val totalTokens: Int = 0,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_streaming")
    val isStreaming: Boolean = false
)