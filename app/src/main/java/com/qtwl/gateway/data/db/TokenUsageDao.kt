package com.qtwl.gateway.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.qtwl.gateway.data.model.TokenUsage
import com.qtwl.gateway.data.model.ApiKeyUsageRow
import kotlinx.coroutines.flow.Flow

@Dao
interface TokenUsageDao {
    @Query("SELECT * FROM token_usage ORDER BY timestamp DESC")
    fun getAllUsage(): Flow<List<TokenUsage>>

    @Query("SELECT * FROM token_usage WHERE provider_id = :providerId ORDER BY timestamp DESC")
    fun getUsageByProvider(providerId: Long): Flow<List<TokenUsage>>

    @Query("SELECT * FROM token_usage WHERE timestamp >= :since ORDER BY timestamp DESC")
    fun getUsageSince(since: Long): Flow<List<TokenUsage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(usage: TokenUsage): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(usages: List<TokenUsage>)

    @Query("SELECT SUM(prompt_tokens) FROM token_usage")
    suspend fun getTotalPromptTokens(): Long

    @Query("SELECT SUM(completion_tokens) FROM token_usage")
    suspend fun getTotalCompletionTokens(): Long

    @Query("SELECT SUM(total_tokens) FROM token_usage")
    suspend fun getTotalTokens(): Long

    @Query("SELECT SUM(total_tokens) FROM token_usage WHERE provider_id = :providerId")
    suspend fun getTotalTokensByProvider(providerId: Long): Long

    @Query("SELECT SUM(total_tokens) FROM token_usage WHERE model_id = :modelId")
    suspend fun getTotalTokensByModel(modelId: String): Long

    @Query("SELECT SUM(upload_bytes) FROM token_usage")
    suspend fun getTotalUploadBytes(): Long

    @Query("SELECT SUM(download_bytes) FROM token_usage")
    suspend fun getTotalDownloadBytes(): Long

    @Query("SELECT SUM(upload_bytes) FROM token_usage WHERE model_id = :modelId")
    suspend fun getTotalUploadBytesByModel(modelId: String): Long

    @Query("SELECT SUM(download_bytes) FROM token_usage WHERE model_id = :modelId")
    suspend fun getTotalDownloadBytesByModel(modelId: String): Long

    @Query("DELETE FROM token_usage WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM token_usage")
    suspend fun clearAll()

    @Query("SELECT * FROM token_usage ORDER BY timestamp DESC")
    suspend fun getAllUsageOnce(): List<TokenUsage>

    @Query("SELECT api_key_label AS apiKeyLabel, SUM(total_tokens) as total, SUM(prompt_tokens) as prompt, SUM(completion_tokens) as completion, SUM(upload_bytes) as upload, SUM(download_bytes) as download, COUNT(*) as calls FROM token_usage GROUP BY api_key_label ORDER BY total DESC")
    suspend fun getUsageByApiKey(): List<ApiKeyUsageRow>

    @Query("SELECT SUM(total_tokens) FROM token_usage WHERE api_key_label = :label")
    suspend fun getTotalTokensByApiKey(label: String): Long

    @Query("SELECT COUNT(*) FROM token_usage WHERE api_key_label = :label")
    suspend fun getCallCountByApiKey(label: String): Int

    @Query("DELETE FROM token_usage WHERE api_key_label = :label")
    suspend fun deleteByApiKey(label: String)
}