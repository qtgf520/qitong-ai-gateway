package com.qtwl.gateway.capture

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * RikkaHub风格抓包引擎
 * 四维捕获：IN入站 → OUT出站 → RESP响应 → FAILOVER故障转移
 */

// ═══════════ 数据模型 ═══════════

@Serializable
data class CaptureIn(
    val id: String = UUID.randomUUID().toString().take(8),
    val timestamp: Long = System.currentTimeMillis(),
    val sourceIp: String = "",
    val method: String = "",
    val path: String = "",
    val headers: String = "",
    val body: String = "",
    val bodySize: Int = 0
)

@Serializable
data class CaptureOut(
    val id: String = UUID.randomUUID().toString().take(8),
    val timestamp: Long = System.currentTimeMillis(),
    val targetUrl: String = "",
    val modelId: String = "",
    val method: String = "POST",
    val headers: String = "",
    val body: String = "",
    val bodySize: Int = 0
)

@Serializable
data class CaptureResp(
    val id: String = UUID.randomUUID().toString().take(8),
    val timestamp: Long = System.currentTimeMillis(),
    val httpStatus: Int = 0,
    val elapsedMs: Long = 0,
    val headers: String = "",
    val body: String = "",
    val bodySize: Int = 0,
    val modelId: String = "",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val isStream: Boolean = false
)

@Serializable
data class CaptureFailover(
    val id: String = UUID.randomUUID().toString().take(8),
    val timestamp: Long = System.currentTimeMillis(),
    val attempts: List<FailoverAttempt> = emptyList(),
    val finalModel: String = "",
    val finalStatus: Int = 0
)

@Serializable
data class FailoverAttempt(
    val index: Int,
    val modelId: String,
    val error: String,
    val elapsedMs: Long
)

@Serializable
data class PacketRecord(
    val id: String = UUID.randomUUID().toString().take(8),
    val timestamp: Long = System.currentTimeMillis(),
    val inbound: CaptureIn? = null,
    val outbound: CaptureOut? = null,
    val response: CaptureResp? = null,
    val failover: CaptureFailover? = null
) {
    val isComplete: Boolean get() = inbound != null && (response != null || failover != null)
    val totalMs: Long get() {
        val start = inbound?.timestamp ?: return -1
        val end = response?.timestamp ?: failover?.timestamp ?: return -1
        return end - start
    }
    val summary: String get() {
        val model = outbound?.modelId ?: "?"
        val status = response?.httpStatus?.toString() ?: (if (failover != null) "FAILOVER" else "…")
        val ms = totalMs.let { if (it >= 0) "${it}ms" else "" }
        val method = inbound?.method ?: "?"
        val path = inbound?.path?.take(40) ?: ""
        return "[$status] $method $path → $model $ms"
    }
}

// ═══════════ 管理器 ═══════════

object PacketCapture {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private const val MAX_RECORDS = 500

    private val _records = CopyOnWriteArrayList<PacketRecord>()

    val records: List<PacketRecord> get() = _records.toList()

    private val pending = ThreadLocal<PacketRecord>()

    fun begin(): PacketRecord {
        val record = PacketRecord()
        pending.set(record)
        return record
    }

    fun captureIn(sourceIp: String, method: String, path: String,
                  headers: String, body: String, bodySize: Int) {
        val record = pending.get() ?: begin()
        val cap = CaptureIn(
            sourceIp = sourceIp, method = method, path = path,
            headers = headers, body = body.take(1000), bodySize = bodySize
        )
        pending.set(record.copy(inbound = cap))
    }

    fun captureOut(targetUrl: String, modelId: String, method: String = "POST",
                   headers: String, body: String, bodySize: Int) {
        val record = pending.get() ?: begin()
        val cap = CaptureOut(
            targetUrl = targetUrl, modelId = modelId, method = method,
            headers = headers, body = body.take(1000), bodySize = bodySize
        )
        pending.set(record.copy(outbound = cap))
    }

    fun captureResp(httpStatus: Int, elapsedMs: Long, headers: String,
                    body: String, bodySize: Int, modelId: String = "",
                    promptTokens: Int = 0, completionTokens: Int = 0,
                    isStream: Boolean = false) {
        val record = pending.get() ?: begin()
        val cap = CaptureResp(
            httpStatus = httpStatus, elapsedMs = elapsedMs, headers = headers,
            body = body.take(1000), bodySize = bodySize, modelId = modelId,
            promptTokens = promptTokens, completionTokens = completionTokens,
            isStream = isStream
        )
        val final = record.copy(response = cap)
        commit(final)
    }

    fun captureFailover(attempts: List<FailoverAttempt>, finalModel: String, finalStatus: Int) {
        val record = pending.get() ?: begin()
        val cap = CaptureFailover(
            attempts = attempts, finalModel = finalModel, finalStatus = finalStatus
        )
        val final = record.copy(failover = cap)
        commit(final)
    }

    private fun commit(record: PacketRecord) {
        _records.add(0, record)
        while (_records.size > MAX_RECORDS) {
            _records.removeAt(_records.size - 1)
        }
        pending.remove()
    }

    fun clear() { _records.clear(); pending.remove() }

    fun exportJson(): String = json.encodeToString(_records.toList())

    fun filterByModel(modelId: String): List<PacketRecord> =
        _records.filter { it.outbound?.modelId == modelId }

    fun filterByStatus(httpStatus: Int): List<PacketRecord> =
        _records.filter { it.response?.httpStatus == httpStatus }

    fun search(keyword: String): List<PacketRecord> =
        _records.filter { record ->
            val searchTarget = buildString {
                append(record.inbound?.body ?: "")
                append(record.inbound?.path ?: "")
                append(record.outbound?.targetUrl ?: "")
                append(record.outbound?.body ?: "")
                append(record.response?.body ?: "")
            }
            searchTarget.contains(keyword, ignoreCase = true)
        }
}