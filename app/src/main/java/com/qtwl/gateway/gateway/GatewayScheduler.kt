package com.qtwl.gateway.gateway

import com.qtwl.gateway.data.db.AppDatabase
import com.qtwl.gateway.data.model.AiModel
import com.qtwl.gateway.data.model.ModelRouteKey
import com.qtwl.gateway.data.model.findByRouteKey
import com.qtwl.gateway.data.model.routeKey
import com.qtwl.gateway.service.GatewayForegroundService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Provider-aware health, speed ranking and failover scheduler. */
object GatewayScheduler {
    private val DEFAULT_CT = "application/json".toMediaType()
    private const val HEALTH_CHECK_TIMEOUT = 5000L
    private const val CACHE_TTL = 60_000L
    private const val BEST_MODEL_TTL = 300_000L
    private const val MODEL_HISTORY_TTL = 30 * 60 * 1000L

    /**
     * Speed ranking in exact provider-scoped form: providerId::modelId.
     * The position in this list is the public ranking ID (1-based in the UI).
     */
    @Volatile
    var pipelineSortedModelKeys: List<String> = emptyList()

    data class ModelHealth(
        val modelId: String,
        val providerId: Long,
        val latencyMs: Long = Long.MAX_VALUE,
        val lastCheckTime: Long = 0,
        val isHealthy: Boolean = true,
        val successCount: Int = 0
    )

    /** Keyed by providerId::modelId, never by modelId alone. */
    val healthCache = mutableMapOf<String, ModelHealth>()
    private var cacheTime: Long = 0

    @Volatile
    private var bestModelKey: String? = null
    @Volatile
    private var bestModelLatency: Long = Long.MAX_VALUE
    private var bestModelSetTime: Long = 0

    data class ModelHistoryRecord(
        val modelId: String,
        val providerId: Long,
        val lastSuccessTime: Long = 0,
        val successCount: Int = 0,
        val failCount: Int = 0,
        val lastFailTime: Long = 0,
        val isHealthy: Boolean = true
    )

    private val modelHistory = mutableMapOf<String, ModelHistoryRecord>()
    private val recentlyUsedModelKeys = mutableSetOf<String>()

    @Volatile
    private var preheatedModels: List<AiModel>? = null
    private var preheatTime: Long = 0
    private const val PREHEAT_TTL = 30_000L

    private fun invalidatePreheat() {
        preheatedModels = null
        preheatTime = 0
    }

    @Volatile
    private var stagedModelKey: String? = null
    private var stagedAt: Long = 0
    private const val STAGE_TIMEOUT = 10_000L

    fun snapshotSwitch(activeModelKey: String?) {
        stagedModelKey = activeModelKey
        stagedAt = System.currentTimeMillis()
    }

    fun commitSwitch() {
        stagedModelKey = null
        stagedAt = 0
    }

    fun rollbackSwitch(): String? {
        val rollback = stagedModelKey
        stagedModelKey = null
        stagedAt = 0
        return rollback
    }

    fun getStaleSwitch(): String? {
        if (stagedModelKey != null && System.currentTimeMillis() - stagedAt > STAGE_TIMEOUT) {
            return rollbackSwitch()
        }
        return null
    }

    enum class DegradeReason {
        UPSTREAM_TIMEOUT, UPSTREAM_4XX, UPSTREAM_5XX,
        JSON_PARSE_FAIL, EMPTY_RESPONSE, STREAM_BROKEN,
        MODEL_SWITCH_FALLBACK, HEALTH_CHECK_FAILED, PROVIDER_DISABLED
    }

    data class DegradeEntry(
        val modelId: String,
        val reason: DegradeReason,
        val detail: String = "",
        val timestamp: Long = System.currentTimeMillis()
    )

    private val degradeLog = mutableListOf<DegradeEntry>()
    private const val DEGRADE_LOG_MAX = 50

    fun logDegrade(modelId: String, reason: DegradeReason, detail: String = "") {
        synchronized(degradeLog) {
            degradeLog.add(DegradeEntry(modelId, reason, detail, System.currentTimeMillis()))
            if (degradeLog.size > DEGRADE_LOG_MAX) degradeLog.removeAt(0)
        }
        if (GatewayForegroundService.getDebugMode()) {
            GatewayForegroundService.addDebugLog("âš ï¸ $modelId: $reason ${if (detail.isNotBlank()) "- $detail" else ""}")
        }
    }

    fun getDegradeLog(): List<DegradeEntry> = synchronized(degradeLog) { degradeLog.toList() }

    fun markModelSuccess(modelId: String, providerId: Long, latencyMs: Long) {
        val key = ModelRouteKey.encode(providerId, modelId)
        synchronized(healthCache) {
            val existing = healthCache[key]
            val successCount = (existing?.successCount ?: 0) + 1
            healthCache[key] = ModelHealth(
                modelId = modelId,
                providerId = providerId,
                latencyMs = latencyMs,
                lastCheckTime = System.currentTimeMillis(),
                isHealthy = true,
                successCount = successCount
            )
        }
        if (latencyMs < bestModelLatency || bestModelKey == null) {
            bestModelKey = key
            bestModelLatency = latencyMs
            bestModelSetTime = System.currentTimeMillis()
        }
        invalidatePreheat()
    }

    /** Legacy overload for non-routed/virtual callers. */
    fun markModelSuccess(modelId: String, latencyMs: Long) =
        markModelSuccess(modelId, 0L, latencyMs)

    fun markModelFailed(modelId: String, providerId: Long) {
        val key = ModelRouteKey.encode(providerId, modelId)
        synchronized(healthCache) {
            healthCache[key] = ModelHealth(
                modelId = modelId,
                providerId = providerId,
                latencyMs = Long.MAX_VALUE,
                lastCheckTime = System.currentTimeMillis(),
                isHealthy = false
            )
        }
        invalidatePreheat()
    }

    fun getBestModel(): String? {
        if (bestModelKey != null && System.currentTimeMillis() - bestModelSetTime < BEST_MODEL_TTL) {
            return bestModelKey
        }
        return null
    }

    fun recordModelResult(modelId: String, providerId: Long, success: Boolean) {
        val key = ModelRouteKey.encode(providerId, modelId)
        synchronized(modelHistory) {
            val existing = modelHistory[key]
            modelHistory[key] = ModelHistoryRecord(
                modelId = modelId,
                providerId = providerId,
                lastSuccessTime = if (success) System.currentTimeMillis() else (existing?.lastSuccessTime ?: 0),
                successCount = (existing?.successCount ?: 0) + if (success) 1 else 0,
                failCount = (existing?.failCount ?: 0) + if (success) 0 else 1,
                lastFailTime = if (!success) System.currentTimeMillis() else (existing?.lastFailTime ?: 0),
                isHealthy = success
            )
        }
    }

    fun recordModelResult(modelId: String, success: Boolean) =
        recordModelResult(modelId, 0L, success)

    fun recordModelUsage(modelId: String, providerId: Long = 0L) {
        recentlyUsedModelKeys.add(ModelRouteKey.encode(providerId, modelId))
    }

    fun getSortedModels(models: List<AiModel>, preferredModelKey: String?): List<AiModel> {
        val preferred = models.findByRouteKey(preferredModelKey)
        val others = models.filter { it.routeKey != preferred?.routeKey }
        val sortedOthers = others.sortedBy { model ->
            val health = synchronized(healthCache) { healthCache[model.routeKey] }
            if (health != null && health.isHealthy) health.latencyMs else Long.MAX_VALUE
        }
        return if (preferred != null) listOf(preferred) + sortedOthers else sortedOthers
    }

    fun smartSort(models: List<AiModel>): List<AiModel> {
        val now = System.currentTimeMillis()
        val tierA = mutableListOf<AiModel>()
        val tierB = mutableListOf<AiModel>()
        val tierC = mutableListOf<AiModel>()
        val tierD = mutableListOf<AiModel>()

        for (model in models) {
            val key = model.routeKey
            val history = synchronized(modelHistory) { modelHistory[key] }
            val isPipelineSuccess = key in pipelineSortedModelKeys
            when {
                isPipelineSuccess && history != null && history.isHealthy -> tierA.add(model)
                history != null && history.successCount > 0 && now - history.lastSuccessTime < MODEL_HISTORY_TTL -> tierB.add(model)
                history != null && history.failCount > 0 -> tierC.add(model)
                else -> tierD.add(model)
            }
        }

        val speedOrder = pipelineSortedModelKeys.withIndex().associate { it.value to it.index }
        return tierA.sortedBy { speedOrder[it.routeKey] ?: Int.MAX_VALUE } +
            tierB.sortedBy { speedOrder[it.routeKey] ?: Int.MAX_VALUE } +
            tierC.sortedBy { speedOrder[it.routeKey] ?: Int.MAX_VALUE } +
            tierD.sortedBy { speedOrder[it.routeKey] ?: Int.MAX_VALUE }
    }

    fun getPreheatedModels(database: AppDatabase, models: List<AiModel>): List<AiModel> {
        val now = System.currentTimeMillis()
        val cached = preheatedModels
        if (cached != null && now - preheatTime < PREHEAT_TTL && cached.isNotEmpty()) {
            if (cached.map { it.routeKey }.toSet() == models.map { it.routeKey }.toSet()) return cached
        }
        val sorted = smartSort(models)
        preheatedModels = sorted
        preheatTime = now
        return sorted
    }

    suspend fun refreshHealthCache(database: AppDatabase) {
        val now = System.currentTimeMillis()
        if (now - cacheTime < CACHE_TTL) return

        val models = database.aiModelDao().getEnabledModelsList().filter { it.isEnabled }
        if (models.isEmpty()) return
        cacheTime = now

        val targetModels = if (recentlyUsedModelKeys.isEmpty()) {
            models
        } else {
            models.filter { it.routeKey in recentlyUsedModelKeys }
        }
        if (targetModels.isEmpty()) return

        coroutineScope {
            targetModels.map { model ->
                async {
                    val key = model.routeKey
                    try {
                        val provider = database.providerDao().getProviderById(model.providerId) ?: return@async
                        if (!provider.isEnabled) return@async
                        val start = System.currentTimeMillis()
                        val resolvedUrl = provider.resolvedBaseUrl.trimEnd('/')
                        val testBody = """{"model":"${model.modelId}","messages":[{"role":"user","content":"hi"}],"max_tokens":1,"stream":false}"""
                        val request = okhttp3.Request.Builder()
                            .url("$resolvedUrl/v1/chat/completions")
                            .post(testBody.toRequestBody(DEFAULT_CT))
                            .apply {
                                if (!provider.apiKey.isNullOrBlank()) {
                                    header("Authorization", "Bearer ${provider.apiKey}")
                                }
                            }
                            .build()
                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(HEALTH_CHECK_TIMEOUT, java.util.concurrent.TimeUnit.MILLISECONDS)
                            .readTimeout(HEALTH_CHECK_TIMEOUT, java.util.concurrent.TimeUnit.MILLISECONDS)
                            .build()
                        val response = client.newCall(request).execute()
                        val latency = System.currentTimeMillis() - start
                        if (response.isSuccessful) {
                            synchronized(healthCache) {
                                healthCache[key] = ModelHealth(model.modelId, model.providerId, latency, now, true)
                            }
                            if (latency < bestModelLatency || bestModelKey == null) {
                                bestModelKey = key
                                bestModelLatency = latency
                                bestModelSetTime = now
                            }
                            if (GatewayForegroundService.getDebugMode()) {
                                GatewayForegroundService.addDebugLog("✓ P${model.providerId} · ${model.modelId}: ${latency}ms")
                            }
                        } else {
                            synchronized(healthCache) {
                                healthCache[key] = ModelHealth(model.modelId, model.providerId, Long.MAX_VALUE, now, false)
                            }
                        }
                        response.close()
                    } catch (_: Exception) {
                        synchronized(healthCache) {
                            healthCache[key] = ModelHealth(
                                model.modelId,
                                model.providerId,
                                Long.MAX_VALUE,
                                System.currentTimeMillis(),
                                false
                            )
                        }
                    }
                }
            }.awaitAll()
        }
        invalidatePreheat()
    }
}