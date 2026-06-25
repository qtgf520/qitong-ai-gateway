package com.qtwl.gateway.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.qtwl.gateway.data.model.ChatMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE conversation_id = :conversationId ORDER BY timestamp ASC")
    fun getMessagesByConversation(conversationId: Long): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE conversation_id = :conversationId ORDER BY timestamp ASC")
    suspend fun getMessagesByConversationList(conversationId: Long): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE id = :id")
    suspend fun getMessageById(id: Long): ChatMessage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessage): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<ChatMessage>)

    @Query("DELETE FROM chat_messages WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: Long)

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE chat_messages SET content = :content, is_streaming = 0, completion_tokens = :completionTokens, total_tokens = :totalTokens WHERE id = :id")
    suspend fun finalizeStreamingMessage(id: Long, content: String, completionTokens: Int, totalTokens: Int)

    @Query("UPDATE chat_messages SET is_streaming = 1 WHERE id = :id")
    suspend fun markStreaming(id: Long)

    @Query("SELECT COUNT(*) FROM chat_messages WHERE conversation_id = :conversationId")
    suspend fun getMessageCount(conversationId: Long): Int

    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    suspend fun getAllMessagesOnce(): List<ChatMessage>

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()
}