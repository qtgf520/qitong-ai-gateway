package com.qtwl.gateway.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.qtwl.gateway.data.model.SpeedHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface SpeedHistoryDao {
    /** 获取指定模型近 N 条测速历史（按时间升序，用于画趋势图） */
    @Query("SELECT * FROM speed_history WHERE model_key = :modelKey ORDER BY measured_at ASC LIMIT :limit")
    fun getHistoryByModel(modelKey: String, limit: Int = 50): Flow<List<SpeedHistory>>

    /** 获取所有模型的最新一条测速记录（用于排行榜快速对照） */
    @Query("SELECT * FROM speed_history WHERE id IN (SELECT MAX(id) FROM speed_history GROUP BY model_key)")
    fun getLatestEachModel(): Flow<List<SpeedHistory>>

    /** 获取指定模型的所有历史记录（一次性，非Flow） */
    @Query("SELECT * FROM speed_history WHERE model_key = :modelKey ORDER BY measured_at ASC")
    suspend fun getHistoryByModelOnce(modelKey: String): List<SpeedHistory>

    /** 插入一条测速记录 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: SpeedHistory): Long

    /** 批量插入 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(histories: List<SpeedHistory>)

    /** 删除超过指定天数的旧记录 */
    @Query("DELETE FROM speed_history WHERE measured_at < :before")
    suspend fun deleteOlderThan(before: Long)

    /** 清空全部历史 */
    @Query("DELETE FROM speed_history")
    suspend fun clearAll()
}