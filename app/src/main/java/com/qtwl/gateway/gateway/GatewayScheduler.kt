package com.qtwl.gateway.gateway

import com.qtwl.gateway.data.db.AppDatabase
import com.qtwl.gateway.data.model.AiModel
import com.qtwl.gateway.network.UpstreamClient
import com.qtwl.gateway.service.GatewayForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * GatewayScheduler — 调度层（旁路观察 + 模型排序 + 健康缓存）
 *
 * 角色：观察者，不是执行者。
 * - 健康检查：异步探测，不阻塞路由
 * - 模型排序：基于历史成功/失败 + 管道测速
 * - 最优模型：自动记住最快的
 *
 * v3.7 — 从 GatewayService 拆出，借鉴进程树"检测进程旁路不插足"原则
 */
object GatewayScheduler {

    private val schedulerJson = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val DEFAULT_CT = "application/json".toMediaType()
    private const val HEALTH_CHECK_TIMEOUT = 5000L
    private const val CACHE_TTL = 60_000L
    private const val BEST_MODEL_TTL = 300_000L
    private const val MODEL_HISTORY_TTL = 30 * 60 * 1000L

    private val failoverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 流水线测速结果排序（最快的在前），由ViewModel更新 */
    @Volatile
    var pipelineSortedModelIds: List<String> = emptyList()

    // ── 健康缓存 ──

    data class ModelHealth(
        val modelId: String,
        val providerId: Long,
        val latencyMs: Long = Long.MAX_VALUE,
        val lastCheckTime: Long = 0,
        val isHealthy: Boolean = true,
        val successCount: Int = 0
    )

    val healthCache = mutableMapOf<String, ModelHealth>()
    private var cacheTime: Long = 0

    @Volatile
    private var bestModelId: String? = null
    @Volatile
    private var bestModelLatency: Long = Long.MAX_VALUE
    private var bestModelSetTime: Long = 0

    // ── 历史记录 ──

    data class ModelHistoryRecord(
        val modelId: String,
        val lastSuccessTime: Long = 0,
        val successCount: Int = 0,
        val failCount: Int = 0,
        val lastFailTime: Long = 0,
        val isHealthy: Boolean = true
    )

    private val modelHistory = mutableMapOf<String, ModelHistoryRecord>()

    // ── 最近使用 ──

    private val recentlyUsedModels = mutableSetOf<String>()

    // ── 超预索引（V5热槽组同构：空闲时预排，请求来直取）──

    @Volatile
    private var preheatedModels: List<AiModel>? = null
    private var preheatTime: Long = 0
    private const val PREHEAT_TTL = 30_000L  // 预排序缓存30秒

    /** 任何健康数据变更时失效预排序缓存 */
    private fun invalidatePreheat() {
        preheatedModels = null
        preheatTime = 0
    }

    // ── 依赖图staging（模型切换原子操作：snapshot→stage→commit）──

    @Volatile
    private var stagedModelId: String? = null
    private var stagedAt: Long = 0
    private const val STAGE_TIMEOUT = 10_000L  // 切换超时10秒自动回滚

    /** 快照：记录当前活跃模型 */
    fun snapshotSwitch(activeModelId: String?) {
        stagedModelId = activeModelId
        stagedAt = System.currentTimeMillis()
    }

    /** 提交：切换成功，清除快照 */
    fun commitSwitch() {
        stagedModelId = null
        stagedAt = 0
    }

    /** 回滚：切换失败，恢复到快照模型 */
    fun rollbackSwitch(): String? {
        val rollback = stagedModelId
        stagedModelId = null
        stagedAt = 0
        return rollback
    }

    /** 检查是否有未完成的切换（超时自动回滚） */
    fun getStaleSwitch(): String? {
        if (stagedModelId != null && System.currentTimeMillis() - stagedAt > STAGE_TIMEOUT) {
            return rollbackSwitch()
        }
        return null
    }

    // ── 降级日志（主动容错：容了要记，不静默）──

    enum class DegradeReason {
        UPSTREAM_TIMEOUT, UPSTREAM_4XX, UPSTREAM_5XX,
        JSON_PARSE_FAIL, EMPTY_RESPONSE, STREAM_BROKEN,
        MODEL_SWITCH_FALLBACK, HEALTH_CHECK_FAILED, PROVIDER_DISABLED
    }

    data class DegradeEntry(
        val modelId: String, val reason: DegradeReason,
        val detail: String = "", val timestamp: Long = System.currentTimeMillis()
    )

    private val degradeLog = mutableListOf<DegradeEntry>()
    private const val DEGRADE_LOG_MAX = 50  // 只保留最近50条

    fun logDegrade(modelId: String, reason: DegradeReason, detail: String = "") {
        synchronized(degradeLog) {
            degradeLog.add(DegradeEntry(modelId, reason, detail, System.currentTimeMillis()))
            if (degradeLog.size > DEGRADE_LOG_MAX) degradeLog.removeAt(0)
        }
        if (GatewayForegroundService.getDebugMode()) {
            GatewayForegroundService.addDebugLog("⚠️ $modelId: $reason ${if (detail.isNotBlank()) "- $detail" else ""}")
        }
    }

    fun getDegradeLog(): List<DegradeEntry> = synchronized(degradeLog) { degradeLog.toList() }

    // ═══════════════════════════════════════════
    // 公共API
    // ═══════════════════════════════════════════

    fun markModelSuccess(modelId: String, latencyMs: Long) {
        synchronized(healthCache) {
            val existing = healthCache[modelId]
            val successCount = (existing?.successCount ?: 0) + 1
            healthCache[modelId] = ModelHealth(modelId, existing?.providerId ?: 0, latencyMs, System.currentTimeMillis(), true, successCount)
        }
        if (latencyMs < bestModelLatency || bestModelId == null) {
            bestModelId = modelId
            bestModelLatency = latencyMs
            bestModelSetTime = System.currentTimeMillis()
        }
        invalidatePreheat()
    }

    fun markModelFailed(modelId: String, providerId: Long) {
        synchronized(healthCache) {
            healthCache[modelId] = ModelHealth(modelId, providerId, Long.MAX_VALUE, System.currentTimeMillis(), false)
        }
        invalidatePreheat()
    }

    fun getBestModel(): String? {
        if (bestModelId != null && System.currentTimeMillis() - bestModelSetTime < BEST_MODEL_TTL) {
            return bestModelId
        }
        return null
    }

    fun recordModelResult(modelId: String, success: Boolean) {
        synchronized(modelHistory) {
            val existing = modelHistory[modelId]
            modelHistory[modelId] = ModelHistoryRecord(
                modelId = modelId,
                lastSuccessTime = if (success) System.currentTimeMillis() else (existing?.lastSuccessTime ?: 0),
                successCount = (existing?.successCount ?: 0) + if (success) 1 else 0,
                failCount = (existing?.failCount ?: 0) + if (success) 0 else 1,
                lastFailTime = if (!success) System.currentTimeMillis() else (existing?.lastFailTime ?: 0),
                isHealthy = success
            )
        }
    }

    fun recordModelUsage(modelId: String) {
        recentlyUsedModels.add(modelId)
    }

    /** 按健康状态排序：快的在前 */
    fun getSortedModels(models: List<AiModel>, preferredModelId: String?): List<AiModel> {
        val preferred = models.find { it.modelId == preferredModelId }
        val others = models.filter { it.modelId != preferredModelId }
        val sortedOthers = others.sortedBy { model ->
            val health = synchronized(healthCache) { healthCache[model.modelId] }
            if (health != null && health.isHealthy) health.latencyMs else Long.MAX_VALUE
        }
        return if (preferred != null) listOf(preferred) + sortedOthers else sortedOthers
    }

    /** 智能排序：a(当前可用) → d(历史成功) → c(有失败记录) → b(从未记录) */
    fun smartSort(models: List<AiModel>): List<AiModel> {
        val now = System.currentTimeMillis()
        val tierA = mutableListOf<AiModel>()
        val tierB = mutableListOf<AiModel>()
        val tierC = mutableListOf<AiModel>()
        val tierD = mutableListOf<AiModel>()

        for (model in models) {
            val history = synchronized(modelHistory) { modelHistory[model.modelId] }
            val isPipelineSuccess = pipelineSortedModelIds.contains(model.modelId)
            when {
                isPipelineSuccess && history != null && history.isHealthy -> tierA.add(model)
                history != null && history.successCount > 0 && (now - history.lastSuccessTime < MODEL_HISTORY_TTL) -> tierB.add(model)
                history != null && history.failCount > 0 -> tierC.add(model)
                else -> tierD.add(model)
            }
        }
        val speedOrder = pipelineSortedModelIds.withIndex().associate { it.value to it.index }
        return (tierA.sortedBy { speedOrder[it.modelId] ?: Int.MAX_VALUE } +
                tierB.sortedBy { speedOrder[it.modelId] ?: Int.MAX_VALUE } +
                tierC.sortedBy { speedOrder[it.modelId] ?: Int.MAX_VALUE } +
                tierD.sortedBy { speedOrder[it.modelId] ?: Int.MAX_VALUE })
    }

    /**
     * 超预索引：优先返回预排序缓存，过期或失效时现场排序并缓存。
     * — 请求来 → 缓存命中 → 零等待
     * — 健康变更 → 自动失效 → 下次请求排序一次 → 缓存30秒
     */
    fun getPreheatedModels(database: AppDatabase, models: List<AiModel>): List<AiModel> {
        val now = System.currentTimeMillis()
        val cached = preheatedModels
        if (cached != null && now - preheatTime < PREHEAT_TTL && cached.isNotEmpty()) {
            // 验证缓存里的模型ID与当前启用列表一致（没有新增/删除模型）
            val cachedIds = cached.map { it.modelId }.toSet()
            val currentIds = models.map { it.modelId }.toSet()
            if (cachedIds == currentIds) return cached
        }
        val sorted = smartSort(models)
        preheatedModels = sorted
        preheatTime = now
        return sorted
    }

    /**
     * 热槽组并行预跑：所有目标模型同时探测，不是挨个测。
     * — coroutineScope + async + awaitAll → 真并行
     * — 全部完成后自动刷新预排序缓存（invalidatePreheat）
     * — 和kotlin-head V5热槽组完全同构：空闲时预跑，请求来直取
     */
    suspend fun refreshHealthCache(database: AppDatabase) {
        val now = System.currentTimeMillis()
        if (now - cacheTime < CACHE_TTL) return

        val models = database.aiModelDao().getEnabledModelsList().filter { it.isEnabled }
        if (models.isEmpty()) return
        cacheTime = now

        val targetModels = if (recentlyUsedModels.isEmpty()) models
        else models.filter { it.modelId in recentlyUsedModels }
        if (targetModels.isEmpty()) return

        // ★ 热槽组并行预跑：所有模型同时探测
        coroutineScope {
            targetModels.map { model ->
                async {
                    try {
                        val provider = database.providerDao().getProviderById(model.providerId) ?: return@async
                        if (!provider.isEnabled) return@async
                        val start = System.currentTimeMillis()
                        val resolvedUrl = provider.resolvedBaseUrl.trimEnd('/')
                        val testBody = """{"model":"${model.modelId}","messages":[{"role":"user","content":"hi"}],"max_tokens":1,"stream":false}"""
                        val req = okhttp3.Request.Builder()
                            .url("$resolvedUrl/v1/chat/completions")
                            .post(testBody.toRequestBody(DEFAULT_CT))
                            .apply { if (!provider.apiKey.isNullOrBlank()) header("Authorization", "Bearer ${provider.apiKey}") }
                            .build()
                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(HEALTH_CHECK_TIMEOUT, java.util.concurrent.TimeUnit.MILLISECONDS)
                            .readTimeout(HEALTH_CHECK_TIMEOUT, java.util.concurrent.TimeUnit.MILLISECONDS)
                            .build()
                        val resp = client.newCall(req).execute()
                        val latency = System.currentTimeMillis() - start
                        if (resp.isSuccessful) {
                            synchronized(healthCache) {
                                healthCache[model.modelId] = ModelHealth(model.modelId, model.providerId, latency, now, true)
                            }
                            if (latency < bestModelLatency || bestModelId == null) {
                                bestModelId = model.modelId
                                bestModelLatency = latency
                                bestModelSetTime = now
                            }
                            if (GatewayForegroundService.getDebugMode()) {
                                GatewayForegroundService.addDebugLog("⏱ ${model.modelId}: ${latency}ms ✅")
                            }
                        } else {
                            synchronized(healthCache) {
                                healthCache[model.modelId] = ModelHealth(model.modelId, model.providerId, Long.MAX_VALUE, now, false)
                            }
                        }
                        resp.close()
                    } catch (_: Exception) {
                        synchronized(healthCache) {
                            healthCache[model.modelId] = ModelHealth(model.modelId, model.providerId, Long.MAX_VALUE, System.currentTimeMillis(), false)
                        }
                    }
                }
            }.awaitAll()
        }
        // 并行探测全部完成 → 刷新预排序缓存
        invalidatePreheat()
    }
}
