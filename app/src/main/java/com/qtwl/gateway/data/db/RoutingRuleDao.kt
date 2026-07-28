package com.qtwl.gateway.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.qtwl.gateway.data.model.RoutingRule
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutingRuleDao {
    @Query("SELECT * FROM routing_rule ORDER BY priority ASC, id ASC")
    fun getAllRules(): Flow<List<RoutingRule>>

    @Query("SELECT * FROM routing_rule WHERE enabled = 1 ORDER BY priority ASC, id ASC")
    suspend fun getEnabledRules(): List<RoutingRule>

    @Query("SELECT * FROM routing_rule WHERE id = :id")
    suspend fun getRule(id: Long): RoutingRule?

    @Query("SELECT * FROM routing_rule WHERE name = :name LIMIT 1")
    suspend fun getRuleByName(name: String): RoutingRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: RoutingRule): Long

    @Update
    suspend fun update(rule: RoutingRule)

    @Delete
    suspend fun delete(rule: RoutingRule)

    @Query("DELETE FROM routing_rule WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE routing_rule SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM routing_rule")
    suspend fun clearAll()
}