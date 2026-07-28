package com.qtwl.gateway.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 测速历史记录实体 —— 保存每次测速的三指标，用于延迟趋势图
 * TTFT: Time To First Token（首字延迟，毫秒）
 * TPS: Tokens Per Second
 * totalMs: 总耗时（毫秒）
 */
@Entity(
    tableName = "speed_history",
    indices = [
        Index("model_key"),     // 按模型查询
        Index("measured_at")    // 按时间排序
    ]
)
data class SpeedHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "model_key")
    val modelKey: String,          // providerId::modelId
    @ColumnInfo(name = "model_name")
    val modelName: String,         // 显示用
    @ColumnInfo(name = "provider_id")
    val providerId: Long,
    @ColumnInfo(name = "ttft_ms")
    val ttftMs: Long,
    @ColumnInfo(name = "tps")
    val tps: Double,
    @ColumnInfo(name = "total_ms")
    val totalMs: Long,
    @ColumnInfo(name = "success")
    val success: Boolean,          // true=测速成功, false=失败
    @ColumnInfo(name = "measured_at")
    val measuredAt: Long = System.currentTimeMillis()
)