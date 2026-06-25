package com.qtwl.gateway.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.qtwl.gateway.data.model.Provider
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY order_index ASC")
    fun getAllProviders(): Flow<List<Provider>>

    @Query("SELECT * FROM providers ORDER BY order_index ASC")
    suspend fun getAllProvidersList(): List<Provider>

    @Query("SELECT * FROM providers WHERE is_enabled = 1 ORDER BY order_index ASC")
    suspend fun getEnabledProviders(): List<Provider>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun getProviderById(id: Long): Provider?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(provider: Provider): Long

    @Update
    suspend fun update(provider: Provider)

    @Delete
    suspend fun delete(provider: Provider)

    @Query("DELETE FROM providers")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(providers: List<Provider>)

    @Query("SELECT * FROM providers ORDER BY order_index ASC")
    suspend fun getAllProvidersOnce(): List<Provider>

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun deleteById(id: Long)
}
