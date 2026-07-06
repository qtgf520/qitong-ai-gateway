package com.qtwl.gateway.utils

import com.qtwl.gateway.service.GatewayForegroundService
import com.qtwl.gateway.gateway.pipelineSortedModelIds

/**
 * 綦小桐工具调用系统
 * 让 qtai-sj 能听懂指令并执行网关操作
 * 支持：测速、切换模型、开关故障转移、开关qtai-sj、查状态等
 */
object ToolExecutor {

    // ==================== 指令解析 ====================
    
    /** 解析用户消息，返回要执行的操作列表 */
    fun parseCommand(text: String): List<ToolAction> {
        val actions = mutableListOf<ToolAction>()
        val lower = text.lowercase()
        
        // 测速相关
        if (lower.contains("测速") || lower.contains("benchmark") || lower.contains("speed")) {
            if (lower.contains("流水") || lower.contains("pipeline") || lower.contains("接力")) {
                actions.add(ToolAction.StartPipelineTest)
            } else {
                actions.add(ToolAction.StartSingleTest)
            }
        }
        
        val modelKeywords = mapOf(
            "claude" to "claude",
            "gemini" to "gemini",
            "gpt" to "gpt",
            "deepseek" to "deepseek",
            "qw" to "qw",
            "通义" to "通义",
            "openai" to "openai",
            "groq" to "groq",
            "ollama" to "ollama"
        )
        for ((keyword, modelId) in modelKeywords) {
            if (lower.contains(keyword)) {
                actions.add(ToolAction.ForceModel(modelId))
                break
            }
        }
        
        // 切换指定模型ID — 支持"切换 step-3.7-flash", "切换 google/diffusiongemma-26b"等
        val switchModelRegex = Regex("切换(?:至|到|\\s+)?\\s+([\\w./-]+)")
        val switchModelMatch = switchModelRegex.find(lower)
        if (switchModelMatch != null) {
            val targetModelId = switchModelMatch.groupValues[1]
            if (targetModelId !in modelKeywords.values) {
                actions.add(ToolAction.ForceModel(targetModelId))
            }
        }
        
        // 数字编号切换 — 支持"切1"、"2"等
        val numRegex = Regex("(?:切|切换)?\\s*(\\d+)\\s*$")
        val numMatch = numRegex.find(text.trim())
        if (numMatch != null) {
            val num = numMatch.groupValues[1].toIntOrNull()
            if (num != null && num > 0 && num <= pipelineSortedModelIds.size) {
                actions.add(ToolAction.ForceModel(pipelineSortedModelIds[num - 1]))
            }
        }
        
        // 上一个/下一个模型
        if (lower.contains("上个模型") || lower.contains("上一个") || lower.contains("prev")) {
            actions.add(ToolAction.SwitchPrevModel)
        }
        if (lower.contains("下个模型") || lower.contains("下一个") || lower.contains("next")) {
            actions.add(ToolAction.SwitchNextModel)
        }
        
        // 故障转移
        if (lower.contains("故障转移") || lower.contains("failover") || lower.contains("自动切换")) {
            if (lower.contains("开") || lower.contains("启")) {
                actions.add(ToolAction.EnableAutoFailover)
            } else if (lower.contains("关") || lower.contains("禁")) {
                actions.add(ToolAction.DisableAutoFailover)
            } else {
                actions.add(ToolAction.ToggleAutoFailover)
            }
        }
        
        // qtai-sj 开关
        if (lower.contains("自动化切换") || lower.contains("qtai")) {
            if (lower.contains("开") || lower.contains("启")) {
                actions.add(ToolAction.EnableQtaiSj)
            } else if (lower.contains("关") || lower.contains("禁")) {
                actions.add(ToolAction.DisableQtaiSj)
            } else {
                actions.add(ToolAction.ToggleQtaiSj)
            }
        }
        
        // 网关开关
        if (lower.contains("启动网关") || lower.contains("开网关")) {
            actions.add(ToolAction.StartGateway)
        }
        if (lower.contains("停止网关") || lower.contains("关网关")) {
            actions.add(ToolAction.StopGateway)
        }
        
        // 查询状态
        if (lower.contains("状态") || lower.contains("status") || lower.contains("运行")) {
            actions.add(ToolAction.QueryStatus)
        }
        if (lower.contains("排行") || lower.contains("rank") || lower.contains("速度")) {
            actions.add(ToolAction.QueryRanking)
        }
        if (lower.contains("当前模型") || lower.contains("用了什么") || lower.contains("当前用")) {
            actions.add(ToolAction.QueryCurrentModel)
        }
        if (lower.contains("流量") || lower.contains("token") || lower.contains("用量")) {
            actions.add(ToolAction.QueryTokenUsage)
        }
        
        return actions
    }
    
    // ==================== 执行操作 ====================
    
    /** 执行工具操作，返回结果文本 */
    fun execute(action: ToolAction, viewModel: com.qtwl.gateway.ui.viewmodel.GatewayViewModel?): String {
        return when (action) {
            is ToolAction.StartPipelineTest -> {
                viewModel?.startPipelineTest()
                "✅ 已启动流水线接力测速，约20秒完成一轮"
            }
            is ToolAction.StartSingleTest -> {
                // 单模型测试（需要指定模型）
                "⚠️ 请指定模型名称，例如：测试Claude模型"
            }
            is ToolAction.ForceModel -> {
                GatewayForegroundService.saveForcedModel(action.modelId)
                GatewayForegroundService.addDebugLog("🔄 AI切换模型 → ${action.modelId}")
                "✅ 已强制指定模型为: ${action.modelId}"
            }
            is ToolAction.SwitchPrevModel -> {
                val switched = switchModel(-1)
                if (switched.isNotBlank()) GatewayForegroundService.addDebugLog("🔄 AI切换模型 ← 上一个: $switched")
                "✅ 已切换到上一个模型"
            }
            is ToolAction.SwitchNextModel -> {
                val switched = switchModel(1)
                if (switched.isNotBlank()) GatewayForegroundService.addDebugLog("🔄 AI切换模型 → 下一个: $switched")
                "✅ 已切换到下一个模型"
            }
            is ToolAction.EnableAutoFailover -> {
                if (!GatewayForegroundService.getAutoFailover()) {
                    GatewayForegroundService.saveAutoFailover(true)
                    "✅ 已开启故障转移，模型失败自动切换"
                } else "⚠️ 故障转移已开启"
            }
            is ToolAction.DisableAutoFailover -> {
                if (GatewayForegroundService.getAutoFailover()) {
                    GatewayForegroundService.saveAutoFailover(false)
                    "✅ 已关闭故障转移"
                } else "⚠️ 故障转移已关闭"
            }
            is ToolAction.ToggleAutoFailover -> {
                GatewayForegroundService.saveAutoFailover(!GatewayForegroundService.getAutoFailover())
                "✅ 故障转移已${if (GatewayForegroundService.getAutoFailover()) "开启" else "关闭"}"
            }
            is ToolAction.EnableQtaiSj -> {
                if (!GatewayForegroundService.getQtaiSjEnabled()) {
                    GatewayForegroundService.saveQtaiSjEnabled(true)
                    "✅ 已开启自动化切换(qtai-sj)"
                } else "⚠️ qtai-sj已开启"
            }
            is ToolAction.DisableQtaiSj -> {
                if (GatewayForegroundService.getQtaiSjEnabled()) {
                    GatewayForegroundService.saveQtaiSjEnabled(false)
                    "✅ 已关闭自动化切换(qtai-sj)"
                } else "⚠️ qtai-sj已关闭"
            }
            is ToolAction.ToggleQtaiSj -> {
                GatewayForegroundService.saveQtaiSjEnabled(!GatewayForegroundService.getQtaiSjEnabled())
                "✅ qtai-sj已${if (GatewayForegroundService.getQtaiSjEnabled()) "开启" else "关闭"}"
            }
            is ToolAction.StartGateway -> {
                viewModel?.startGateway()
                "✅ 网关已启动"
            }
            is ToolAction.StopGateway -> {
                viewModel?.stopGateway()
                "✅ 网关已停止"
            }
            is ToolAction.QueryStatus -> {
                val running = GatewayForegroundService.isServiceRunning
                val port = GatewayForegroundService.getGatewayPort()
                val failover = GatewayForegroundService.getAutoFailover()
                val qtaiSj = GatewayForegroundService.getQtaiSjEnabled()
                "📊 网关状态：${if (running) "运行中" else "已停止"} | 端口: $port | 故障转移: ${if (failover) "开" else "关"} | qtai-sj: ${if (qtaiSj) "开" else "关"}"
            }
            is ToolAction.QueryRanking -> {
                if (pipelineSortedModelIds.isEmpty()) {
                    "⚠️ 暂无测速数据，请先启动测速"
                } else {
                    val list = pipelineSortedModelIds.mapIndexed { i, id -> "  ${i+1}. $id" }.joinToString("\n")
                    "📈 测速排行（共${pipelineSortedModelIds.size}个）：\n$list\n\n回复数字编号（如 1）即可切换到对应模型"
                }
            }
            is ToolAction.QueryCurrentModel -> {
                val forced = GatewayForegroundService.getForcedModel()
                if (forced.isNotBlank()) {
                    "🧠 当前在使用: $forced"
                } else if (pipelineSortedModelIds.isNotEmpty()) {
                    "🧠 当前在使用: ${pipelineSortedModelIds.first()}"
                } else {
                    "⚠️ 暂无模型数据"
                }
            }
            is ToolAction.QueryTokenUsage -> {
                "📊 Token统计功能待实现"
            }
        }
    }
    
    /** 切换模型（基于测速排行），返回切换到的模型ID */
    private fun switchModel(direction: Int): String {
        if (pipelineSortedModelIds.isEmpty()) {
            return ""
        }
        val currentIdx = GatewayForegroundService.getGatewayConfig("current_model_idx", "0").toIntOrNull() ?: 0
        val nextIdx = (currentIdx + direction + pipelineSortedModelIds.size) % pipelineSortedModelIds.size
        GatewayForegroundService.saveGatewayConfig("current_model_idx", nextIdx.toString())
        GatewayForegroundService.saveForcedModel(pipelineSortedModelIds[nextIdx])
        return pipelineSortedModelIds[nextIdx]
    }
}

// ==================== 工具操作类型 ====================

sealed class ToolAction {
    object StartPipelineTest : ToolAction()
    object StartSingleTest : ToolAction()
    data class ForceModel(val modelId: String) : ToolAction()
    object SwitchPrevModel : ToolAction()
    object SwitchNextModel : ToolAction()
    object EnableAutoFailover : ToolAction()
    object DisableAutoFailover : ToolAction()
    object ToggleAutoFailover : ToolAction()
    object EnableQtaiSj : ToolAction()
    object DisableQtaiSj : ToolAction()
    object ToggleQtaiSj : ToolAction()
    object StartGateway : ToolAction()
    object StopGateway : ToolAction()
    object QueryStatus : ToolAction()
    object QueryRanking : ToolAction()
    object QueryCurrentModel : ToolAction()
    object QueryTokenUsage : ToolAction()
}
