package com.qtwl.gateway.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.qtwl.gateway.data.model.AiModel
import kotlinx.coroutines.flow.Flow

@Dao
interface AiModelDao {
    @Query("SELECT * FROM models ORDER BY provider_id ASC, model_id ASC")
    fun getAllModels(): Flow<List<AiModel>>

    @Query("SELECT * FROM models ORDER BY provider_id ASC, model_id ASC")
    suspend fun getAllModelsList(): List<AiModel>

    @Query("SELECT m.* FROM models m INNER JOIN providers p ON m.provider_id = p.id WHERE m.is_enabled = 1 AND p.is_enabled = 1 ORDER BY m.provider_id ASC, m.model_id ASC")
    fun getEnabledModels(): Flow<List<AiModel>>

    @Query("SELECT m.* FROM models m INNER JOIN providers p ON m.provider_id = p.id WHERE m.is_enabled = 1 AND p.is_enabled = 1 ORDER BY m.provider_id ASC, m.model_id ASC")
    suspend fun getEnabledModelsList(): List<AiModel>

    @Query("SELECT * FROM models WHERE provider_id = :providerId ORDER BY model_id ASC")
    suspend fun getModelsByProvider(providerId: Long): List<AiModel>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun getModelById(id: Long): AiModel?

    @Query("SELECT * FROM models WHERE is_default = 1 LIMIT 1")
    suspend fun getDefaultModel(): AiModel?

    @Query("SELECT * FROM models WHERE model_id = :modelId AND provider_id = :providerId LIMIT 1")
    suspend fun getModelByProviderAndId(providerId: Long, modelId: String): AiModel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: AiModel): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(models: List<AiModel>)

    @Update
    suspend fun update(model: AiModel)

    @Delete
    suspend fun delete(model: AiModel)

    @Query("DELETE FROM models WHERE provider_id = :providerId")
    suspend fun deleteByProvider(providerId: Long)

    @Query("UPDATE models SET is_default = 0 WHERE is_default = 1")
    suspend fun clearDefaultModel()

    @Query("UPDATE models SET sync_status = :status WHERE provider_id = :providerId")
    suspend fun updateSyncStatusByProvider(providerId: Long, status: String)

    @Query("SELECT * FROM models ORDER BY provider_id ASC, model_id ASC")
    suspend fun getAllModelsOnce(): List<AiModel>

    @Query("DELETE FROM models")
    suspend fun deleteAll()
}
