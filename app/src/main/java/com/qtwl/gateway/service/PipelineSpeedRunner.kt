package com.qtwl.gateway.service

import com.qtwl.gateway.data.db.AppDatabase
import com.qtwl.gateway.data.model.SpeedMetrics
import com.qtwl.gateway.utils.ModelSpeedTester
import com.qtwl.gateway.utils.PipelineStatusMark
import com.qtwl.gateway.ui.viewmodel.PipelineTestItem
import com.qtwl.gateway.ui.viewmodel._pipelineStatus
import com.qtwl.gateway.ui.viewmodel._pipelineProgress
import com.qtwl.gateway.ui.viewmodel._pipelineRunning
import com.qtwl.gateway.ui.viewmodel._pipelineCountdown
import com.qtwl.gateway.ui.viewmodel.savePipelineCache
import com.qtwl.gateway.gateway.GatewayScheduler
import com.qtwl.gateway.data.model.routeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 后台流水线测速引擎（v3.18.37 核心改造）
 *
 * 根因修复：原测速循环挂在 GatewayViewModel.viewModelScope 上，
 * ViewModel 生命周期 = APP 前台界面，界面不在协程即被取消 →
 * 表现为「只有打开 APP 才测速」。
 *
 * 本引擎自带独立作用域，由常驻前台服务 GatewayForegroundService 持有并启动，
 * 服务活着测速就一直后台循环（按用户配置间隔 5分钟~4小时），
 * 状态仍写回包级 StateFlow（UI 直接 collect，零改动）。
 */
object PipelineSpeedRunner {
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    @Volatile private var running = false

    private val speedTester = ModelSpeedTester()

    @Synchronized
    fun start(database: AppDatabase) {
        if (running) return
        running = true
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        job = s.launch {
            _pipelineRunning.value = true
            com.qtwl.gateway.ui.viewmodel._qtaiSjStatus.value =
                com.qtwl.gateway.ui.viewmodel._qtaiSjStatus.value.copy(isTesting = true)
            try {
                var firstRound = true
                while (isActive && running) {
                    // ★ 获取启用模型列表
                    val enabledList = withContext(Dispatchers.IO) {
                        database.aiModelDao().getEnabledModelsList().filter { it.isEnabled }
                    }
                    if (enabledList.isEmpty()) {
                        _pipelineRunning.value = false
                        running = false
                        return@launch
                    }
                    // ★ 保留旧结果，新模型加入等待
                    val oldStatus = _pipelineStatus.value
                    val oldMap = oldStatus.associateBy { it.selectionKey }
                    _pipelineStatus.value = enabledList.map { model ->
                        oldMap[model.routeKey]?.copy(
                            modelName = model.customAlias.ifBlank { model.displayName },
                            providerId = model.providerId,
                        ) ?: PipelineTestItem(
                            modelId = model.modelId,
                            modelName = model.customAlias.ifBlank { model.displayName },
                            providerId = model.providerId,
                            status = PipelineStatusMark.WAIT + "等待中"
                        )
                    }
                    val sortedList = if (firstRound) enabledList
                    else {
                        val speedMap = _pipelineStatus.value.associate { it.selectionKey to it.latencyMs }
                        enabledList.sortedBy { speedMap[it.routeKey] ?: Long.MAX_VALUE }
                    }
                    for (model in sortedList) {
                        if (!isActive || !running) break
                        val realIdx = _pipelineStatus.value.indexOfFirst { it.selectionKey == model.routeKey }
                        if (realIdx < 0) continue
                        // 进度
                        val testedCount = _pipelineStatus.value.count { PipelineStatusMark.isDone(it.status) }
                        val total = _pipelineStatus.value.size
                        _pipelineProgress.value = if (total > 0) testedCount.toFloat() / total.toFloat() else 0f
                        val cur = _pipelineStatus.value.toMutableList()
                        cur[realIdx] = cur[realIdx].copy(status = PipelineStatusMark.TEST + "测速中...", isCurrent = true)
                        for (i in cur.indices) { if (i != realIdx) cur[i] = cur[i].copy(isCurrent = false) }
                        _pipelineStatus.value = cur

                        val provider = withContext(Dispatchers.IO) {
                            database.providerDao().getProviderById(model.providerId)
                        }
                        if (provider == null || !provider.isEnabled) {
                            val sk = _pipelineStatus.value.toMutableList()
                            sk[realIdx] = sk[realIdx].copy(status = PipelineStatusMark.FAIL + "服务商不可用", latencyMs = Long.MAX_VALUE, isCurrent = false)
                            _pipelineStatus.value = sk
                            continue
                        }
                        // 三指标测速
                        var success = false; var latency = 0L; var errorMsg = ""
                        var ttft = 0L
                        try {
                            withContext(Dispatchers.IO) {
                                val resolvedUrl = provider.resolvedBaseUrl.trimEnd('/')
                                val metrics = speedTester.measure(model.modelId, resolvedUrl, provider.apiKey, provider.chatPath)
                                latency = metrics.totalMs
                                ttft = metrics.ttftMs
                                if (metrics.ttftMs >= 0) success = true else errorMsg = "测速失败"
                            }
                        } catch (e: Exception) { errorMsg = e.message?.take(60) ?: "超时" }
                        // 记录测速历史（写库，供趋势图）
                        if (ttft != 0L || latency != 0L) {
                            try {
                                val history = com.qtwl.gateway.data.model.SpeedHistory(
                                    modelKey = model.routeKey,
                                    modelName = model.customAlias.ifBlank { model.displayName },
                                    providerId = model.providerId,
                                    ttftMs = if (success) ttft else -1,
                                    tps = if (success) 0.0 else 0.0,
                                    totalMs = if (success) latency else -1,
                                    success = success,
                                    measuredAt = System.currentTimeMillis()
                                )
                                withContext(Dispatchers.IO) {
                                    database.speedHistoryDao().insert(history)
                                    val weekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
                                    database.speedHistoryDao().deleteOlderThan(weekAgo)
                                }
                            } catch (_: Exception) { }
                        }
                        val rl = _pipelineStatus.value.toMutableList()
                        rl[realIdx] = rl[realIdx].copy(
                            status = if (success) PipelineStatusMark.OK + "TTFT=${ttft}ms ${latency}ms" else PipelineStatusMark.FAIL + errorMsg,
                            latencyMs = if (success) latency else Long.MAX_VALUE, isCurrent = false
                        )
                        _pipelineStatus.value = rl
                    }
                    // 排序 + 缓存
                    val sorted = _pipelineStatus.value.sortedBy { it.latencyMs }
                    _pipelineStatus.value = sorted
                    GatewayScheduler.pipelineSortedModelKeys = sorted.map { it.selectionKey }
                    savePipelineCache(sorted)
                    firstRound = false
                    _pipelineRunning.value = false
                    // 间隔
                    val intervalMinutes = GatewayForegroundService.getPipelineInterval()
                    val totalSeconds = (intervalMinutes * 60).coerceAtLeast(10)
                    _pipelineCountdown.value = totalSeconds
                    try {
                        while (_pipelineCountdown.value > 0 && isActive && running) {
                            delay(1000)
                            _pipelineCountdown.value--
                        }
                    } catch (_: Exception) {
                        _pipelineCountdown.value = 0
                        running = false
                        return@launch
                    }
                    _pipelineCountdown.value = 0
                    _pipelineRunning.value = true
                }
            } catch (_: Exception) {
                _pipelineRunning.value = false
                _pipelineCountdown.value = 0
            }
            running = false
            _pipelineRunning.value = false
        }
    }

    /** 停止（用户手动停 / 服务销毁） */
    @Synchronized
    fun stop() {
        running = false
        _pipelineRunning.value = false
        _pipelineCountdown.value = 0
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
    }

    fun isRunning(): Boolean = running
}