package com.qtwl.gateway.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.qtwl.gateway.data.model.Conversation
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updated_at DESC")
    fun getAllConversations(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations ORDER BY updated_at DESC")
    suspend fun getAllConversationsList(): List<Conversation>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversationById(id: Long): Conversation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: Conversation): Long

    @Update
    suspend fun update(conversation: Conversation)

    @Delete
    suspend fun delete(conversation: Conversation)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE conversations SET updated_at = :timestamp, message_count = message_count + 1 WHERE id = :id")
    suspend fun touchConversation(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("UPDATE conversations SET total_tokens = total_tokens + :tokens WHERE id = :id")
    suspend fun addTokens(id: Long, tokens: Int)

    @Query("SELECT * FROM conversations ORDER BY updated_at DESC")
    suspend fun getAllConversationsOnce(): List<Conversation>

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(conversations: List<Conversation>)
}