package com.qtwl.gateway.data.model

import kotlinx.serialization.Serializable

/**
 * 测速结果 — 三指标
 * TTFT: Time To First Token（首字延迟，毫秒）
 * TPS: Tokens Per Second（首字之后每秒token数）
 * totalMs: 总耗时（毫秒）
 */
@Serializable
data class SpeedMetrics(
    val ttftMs: Long,
    val tps: Double,
    val totalMs: Long,
    val tokenCount: Int,
    val measuredAt: Long,
) {
    companion object {
        val EMPTY = SpeedMetrics(0L, 0.0, 0L, 0, 0L)
    }
}