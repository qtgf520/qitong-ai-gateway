package com.qtwl.gateway.utils

import com.qtwl.gateway.gateway.GatewayScheduler
import com.qtwl.gateway.service.GatewayForegroundService

/**
 * 綦小桐工具调用系统 v2（技能编码版）
 * =====================================
 * 硬指令全部技能化+编码化：
 * - 用户自由表达 → 大脑理解 → 大脑输出【指令:编码】→ 系统执行
 * - 编码对外不可见，技能池在 SkillRegistry 中管理
 * - 支持按编码执行 + 按旧式关键词匹配（兼容）
 */
object ToolExecutor {

    // ==================== 从大脑回复提取技能编码 ====================

    /** 从文本中提取【指令:编码】指令，返回编码列表（支持【指令:编码:参数】格式） */
    fun extractSkillCodes(text: String): List<String> {
        return Regex("【指令:(\\d{6})(?::([^】]+))?】").findAll(text).map { it.groupValues[1] }.toList()
    }

    /** 从文本中提取带参数的技能指令，返回 (编码, 参数) 列表 */
    fun extractSkillCodesWithParams(text: String): List<Pair<String, String>> {
        return Regex("【指令:(\\d{6})(?::([^】]+))?】").findAll(text).map {
            val code = it.groupValues[1]
            val param = it.groupValues[2].trim()
            code to param
        }.toList()
    }

    /** 检查文本是否包含技能编码 */
    fun hasSkillCode(text: String): Boolean = Regex("【指令:\\d{6}(?::[^】]+)?】").containsMatchIn(text)

    // ==================== 按照技能编码执行 ====================

    /** 根据技能编码执行操作，返回执行结果文本 */
    fun executeByCode(code: String, param: String = "", viewModel: com.qtwl.gateway.ui.viewmodel.GatewayViewModel? = null): String {
        return when (code) {
            // ========== 1xxxxx 测速 ==========
            "100001" -> {
                if (param.isNotBlank()) {
                    val modelId = GatewayScheduler.pipelineSortedModelIds.firstOrNull { it.contains(param, ignoreCase = true) }
                    if (modelId != null) {
                        val result = "✅ 正在对 $modelId 进行单次测速"
                        GatewayForegroundService.addDebugLog("📊 大脑测速: $modelId")
                        result
                    } else {
                        "⚠️ 未找到匹配\"$param\"的模型"
                    }
                } else {
                    "⚠️ 请指定模型名称，例如：测试Claude模型"
                }
            }
            "100002" -> {
                viewModel?.startPipelineTest()
                "✅ 已启动流水线接力测速，约20秒完成一轮"
            }
            "100003" -> {
                viewModel?.stopPipelineTest()
                "✅ 已停止测速"
            }

            // ========== 2xxxxx 模型切换 ==========
            "200001" -> {
                if (param.isNotBlank()) {
                    val idx = param.toIntOrNull()
                    if (idx != null && idx > 0 && idx <= GatewayScheduler.pipelineSortedModelIds.size) {
                        val modelId = GatewayScheduler.pipelineSortedModelIds[idx - 1]
                        GatewayForegroundService.saveForcedModel(modelId)
                        GatewayForegroundService.activeNodeName = modelId
                        GatewayForegroundService.addDebugLog("🔄 大脑切换 → 排行第${idx}: $modelId")
                        "✅ 已切换到排行第${idx}的模型: $modelId"
                    } else {
                        "⚠️ 编号超出范围（1-${GatewayScheduler.pipelineSortedModelIds.size}）"
                    }
                } else {
                    "⚠️ 请指定排行编号，例如：切换到第1个"
                }
            }
            "200002" -> {
                if (param.isNotBlank()) {
                    // 模糊匹配模型ID
                    val matched = GatewayScheduler.pipelineSortedModelIds.firstOrNull { it.contains(param, ignoreCase = true) }
                    if (matched != null) {
                        GatewayForegroundService.saveForcedModel(matched)
                        GatewayForegroundService.activeNodeName = matched
                        GatewayForegroundService.addDebugLog("🔄 大脑切换 → 匹配: $param → $matched")
                        "✅ 已切换到模型: $matched"
                    } else {
                        "⚠️ 未找到匹配\"$param\"的模型，可用排行查看"
                    }
                } else {
                    "⚠️ 请指定要切换的模型名称"
                }
            }
            "200003" -> {
                val switched = switchModel(-1)
                if (switched.isNotBlank()) {
                    GatewayForegroundService.activeNodeName = switched
                    GatewayForegroundService.addDebugLog("🔄 AI切换模型 ← 上一个: $switched")
                }
                "✅ 已切换到上一个模型"
            }
            "200004" -> {
                val switched = switchModel(1)
                if (switched.isNotBlank()) {
                    GatewayForegroundService.activeNodeName = switched
                    GatewayForegroundService.addDebugLog("🔄 AI切换模型 → 下一个: $switched")
                }
                "✅ 已切换到下一个模型"
            }
            "200005" -> {
                GatewayForegroundService.saveForcedModel("")
                "✅ 已清除强制模型，恢复自动选择"
            }

            // ========== 3xxxxx 网关&故障转移 ==========
            "300001" -> {
                GatewayForegroundService.saveAutoFailover(true)
                "✅ 已开启故障转移"
            }
            "300002" -> {
                GatewayForegroundService.saveAutoFailover(false)
                "✅ 已关闭故障转移"
            }
            "300003" -> {
                val new = !GatewayForegroundService.getAutoFailover()
                GatewayForegroundService.saveAutoFailover(new)
                "✅ 故障转移已${if (new) "开启" else "关闭"}"
            }
            "300004" -> {
                viewModel?.startGateway()
                "✅ 网关已启动"
            }
            "300005" -> {
                viewModel?.stopGateway()
                "✅ 网关已停止"
            }
            "300006" -> {
                if (GatewayForegroundService.isServiceRunning) {
                    viewModel?.stopGateway()
                    "✅ 网关已停止"
                } else {
                    viewModel?.startGateway()
                    "✅ 网关已启动"
                }
            }
            "300007" -> "⚠️ 请指定端口号，例如：设置端口为 8890"
            "300008" -> "⚠️ 唤醒保活功能请在APP中操作"
            "300009" -> "⚠️ 唤醒保活功能请在APP中操作"

            // ========== 4xxxxx 代理 ==========
            "400001" -> {
                GatewayForegroundService.saveProxyEnabled(true)
                "✅ 已开启代理"
            }
            "400002" -> {
                GatewayForegroundService.saveProxyEnabled(false)
                "✅ 已关闭代理"
            }
            "400003" -> {
                val new = !GatewayForegroundService.isProxyEnabled()
                GatewayForegroundService.saveProxyEnabled(new)
                "✅ 代理已${if (new) "开启" else "关闭"}"
            }
            "400004" -> "⚠️ 代理测速功能请到代理配置页面操作"

            // ========== 5xxxxx 数据 ==========
            "500001" -> {
                viewModel?.backupData()
                "✅ 正在备份数据..."
            }
            "500002" -> "⚠️ 恢复功能请在数据管理页面操作"
            "500003" -> "⚠️ 重置将清空所有数据，请在数据管理页面确认操作"
            "500004" -> {
                viewModel?.clearAllUsage()
                "✅ 已清理所有统计"
            }

            // ========== 6xxxxx 查询 ==========
            "600001" -> {
                val running = GatewayForegroundService.isServiceRunning
                val port = GatewayForegroundService.getGatewayPort()
                val failover = GatewayForegroundService.getAutoFailover()
                val qtaiSj = GatewayForegroundService.getQtaiSjEnabled()
                "📊 网关状态：${if (running) "运行中" else "已停止"} | 端口: $port | 故障转移: ${if (failover) "开" else "关"} | qtai-sj: ${if (qtaiSj) "开" else "关"}"
            }
            "600002" -> {
                if (GatewayScheduler.pipelineSortedModelIds.isEmpty()) {
                    "⚠️ 暂无测速数据，请先启动测速"
                } else {
                    val list = GatewayScheduler.pipelineSortedModelIds.mapIndexed { i, id -> "  ${i+1}. $id" }.joinToString("\n")
                    "📈 测速排行（共${GatewayScheduler.pipelineSortedModelIds.size}个）：\n$list"
                }
            }
            "600003" -> {
                val forced = GatewayForegroundService.getForcedModel()
                if (forced.isNotBlank()) {
                    "🧠 当前在使用: $forced"
                } else if (GatewayScheduler.pipelineSortedModelIds.isNotEmpty()) {
                    "🧠 当前在使用: ${GatewayScheduler.pipelineSortedModelIds.first()}"
                } else {
                    "⚠️ 暂无模型数据"
                }
            }
            "600004" -> {
                val up = GatewayForegroundService.trafficUploadBytes.get()
                val down = GatewayForegroundService.trafficDownloadBytes.get()
                val totalUp = GatewayForegroundService.totalUploadBytes.get()
                val totalDown = GatewayForegroundService.totalDownloadBytes.get()
                "📊 当前会话 ↑${formatBytes(up)} ↓${formatBytes(down)}\n📈 总统计 ↑${formatBytes(totalUp)} ↓${formatBytes(totalDown)}"
            }
            "600005" -> {
                val tokens = GatewayForegroundService.tokenPromptInput + GatewayForegroundService.tokenCompletionOutput
                "📊 Token: 输入${GatewayForegroundService.tokenPromptInput} / 输出${GatewayForegroundService.tokenCompletionOutput} / 总计$tokens"
            }
            "600006" -> {
                val logs = GatewayForegroundService.getDebugLogs()
                if (logs.isEmpty()) "📋 暂无调试日志" else "📋 最近日志：\n" + logs.joinToString("\n")
            }
            "600007" -> "⚠️ 请到服务商管理页面查看详细列表"
            "600008" -> "⚠️ 请到模型管理页面查看详细列表"

            // ========== 7xxxxx 导航 ==========
            "700001" -> "🔗 请打开APP到服务商管理页面"
            "700002" -> "🔗 请打开APP到代理配置页面"
            "700003" -> "🔗 请打开APP到数据管理页面"
            "700004" -> "🔗 请打开APP到统计页面"
            "700005" -> "🔗 请打开APP到抓包调试页面"
            "700006" -> "🔗 请打开APP到关于页面"
            "700007" -> "🔗 请打开APP到聊天界面"
            "700008" -> "🔗 请打开APP到首页"
            "700009" -> "🔗 请打开APP到测速管理页面"

            // ========== 8xxxxx 服务商管理 ==========
            "800001" -> "⚠️ 请到服务商管理页面手动添加"
            "800002" -> "⚠️ 请到服务商管理页面同步模型"
            "800003" -> "⚠️ 请指定要启用的模型名称"
            "800004" -> "⚠️ 请指定要禁用的模型名称"
            "800005" -> "⚠️ 请到模型管理页面修改别名"
            "800006" -> "⚠️ 请在服务商管理页面删除"

            // ========== 9xxxxx 高级 ==========
            "900001" -> {
                com.qtwl.gateway.service.GroupChatManager.setEnabled(true)
                "✅ 已开启群聊模式"
            }
            "900002" -> {
                com.qtwl.gateway.service.GroupChatManager.setEnabled(false)
                "✅ 已关闭群聊模式"
            }
            "900003" -> {
                com.qtwl.gateway.service.ThinkingConfigManager.setEnabled(true)
                "✅ 已开启思考引导"
            }
            "900004" -> {
                com.qtwl.gateway.service.ThinkingConfigManager.setEnabled(false)
                "✅ 已关闭思考引导"
            }
            "900005" -> {
                com.qtwl.gateway.ui.viewmodel.BrainMemoryManager.updateConfig(
                    com.qtwl.gateway.ui.viewmodel.BrainMemoryManager.getConfig().copy(enabled = true)
                )
                "✅ 已开启大脑记忆"
            }
            "900006" -> {
                com.qtwl.gateway.ui.viewmodel.BrainMemoryManager.updateConfig(
                    com.qtwl.gateway.ui.viewmodel.BrainMemoryManager.getConfig().copy(enabled = false)
                )
                "✅ 已关闭大脑记忆"
            }
            "900007" -> {
                GatewayForegroundService.saveDebugMode(true)
                "✅ 已开启调试模式"
            }
            "900008" -> {
                GatewayForegroundService.saveDebugMode(false)
                "✅ 已关闭调试模式"
            }
            "900009" -> {
                val new = !GatewayForegroundService.getQtaiSjEnabled()
                GatewayForegroundService.saveQtaiSjEnabled(new)
                "✅ qtai-sj已${if (new) "开启" else "关闭"}"
            }
            "900010" -> {
                GatewayForegroundService.saveRequireApiKey(true)
                "✅ 已开启API密钥验证"
            }
            "900011" -> {
                GatewayForegroundService.saveRequireApiKey(false)
                "✅ 已关闭API密钥验证"
            }

            else -> "⚠️ 未知技能编码: $code"
        }
    }

    // ==================== 传统指令解析（兼容+大脑辅助） ====================

    /** 解析用户消息，返回要执行的操作列表（简化版，仅供兼容） */
    fun parseCommand(text: String): List<ToolAction> {
        val actions = mutableListOf<ToolAction>()
        val lower = text.lowercase()

        // 先检查是否有技能编码格式
        val codes = extractSkillCodes(text)
        if (codes.isNotEmpty()) {
            codes.forEach { code ->
                when (code) {
                    "100002" -> actions.add(ToolAction.StartPipelineTest)
                    "200003" -> actions.add(ToolAction.SwitchPrevModel)
                    "200004" -> actions.add(ToolAction.SwitchNextModel)
                    "200005" -> actions.add(ToolAction.ClearForcedModel)
                    "300001" -> actions.add(ToolAction.EnableAutoFailover)
                    "300002" -> actions.add(ToolAction.DisableAutoFailover)
                    "300003" -> actions.add(ToolAction.ToggleAutoFailover)
                    "300004" -> actions.add(ToolAction.StartGateway)
                    "300005" -> actions.add(ToolAction.StopGateway)
                    "300006" -> actions.add(ToolAction.ToggleGateway)
                    "400001" -> actions.add(ToolAction.EnableProxy)
                    "400002" -> actions.add(ToolAction.DisableProxy)
                    "500004" -> actions.add(ToolAction.ClearTokenUsage)
                    "600001" -> actions.add(ToolAction.QueryStatus)
                    "600002" -> actions.add(ToolAction.QueryRanking)
                    "600003" -> actions.add(ToolAction.QueryCurrentModel)
                    "600004" -> actions.add(ToolAction.QueryTokenUsage)
                    "600005" -> actions.add(ToolAction.QueryTokenUsage)
                    "900001" -> actions.add(ToolAction.EnableGroupChat)
                    "900002" -> actions.add(ToolAction.DisableGroupChat)
                    "900009" -> actions.add(ToolAction.ToggleQtaiSj)
                    "900007" -> actions.add(ToolAction.EnableDebug)
                    "900008" -> actions.add(ToolAction.DisableDebug)
                }
            }
            if (actions.isNotEmpty()) return actions
        }

        // 传统关键词匹配（保留兼容）
        if (lower.contains("测速") || lower.contains("benchmark") || lower.contains("speed")) {
            if (lower.contains("流水") || lower.contains("pipeline") || lower.contains("接力")) {
                actions.add(ToolAction.StartPipelineTest)
            }
        }
        val switchModelRegex = Regex("切换(?:至|到|\\s+)?\\s+([\\w./-]+)")
        val switchModelMatch = switchModelRegex.find(lower)
        if (switchModelMatch != null) {
            val targetModelId = switchModelMatch.groupValues[1]
            actions.add(ToolAction.ForceModel(targetModelId))
            return actions
        }
        val numRegex = Regex("(?:切|切换)?\\s*(\\d+)\\s*$")
        val numMatch = numRegex.find(text.trim())
        if (numMatch != null) {
            val num = numMatch.groupValues[1].toIntOrNull()
            if (num != null && num > 0 && num <= GatewayScheduler.pipelineSortedModelIds.size) {
                actions.add(ToolAction.ForceModel(GatewayScheduler.pipelineSortedModelIds[num - 1]))
                return actions
            }
        }
        if (lower.contains("上一个") || lower.contains("prev")) actions.add(ToolAction.SwitchPrevModel)
        if (lower.contains("下一个") || lower.contains("next")) actions.add(ToolAction.SwitchNextModel)

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
            is ToolAction.StartSingleTest -> "⚠️ 请指定模型名称，例如：测试Claude模型"
            is ToolAction.ForceModel -> {
                GatewayForegroundService.saveForcedModel(action.modelId)
                GatewayForegroundService.activeNodeName = action.modelId
                GatewayForegroundService.addDebugLog("🔄 AI切换模型 → ${action.modelId}")
                "✅ 已强制指定模型为: ${action.modelId}"
            }
            is ToolAction.SwitchPrevModel -> {
                val switched = switchModel(-1)
                if (switched.isNotBlank()) {
                    GatewayForegroundService.activeNodeName = switched
                    GatewayForegroundService.addDebugLog("🔄 AI切换模型 ← 上一个: $switched")
                }
                "✅ 已切换到上一个模型"
            }
            is ToolAction.SwitchNextModel -> {
                val switched = switchModel(1)
                if (switched.isNotBlank()) {
                    GatewayForegroundService.activeNodeName = switched
                    GatewayForegroundService.addDebugLog("🔄 AI切换模型 → 下一个: $switched")
                }
                "✅ 已切换到下一个模型"
            }
            is ToolAction.EnableAutoFailover -> {
                GatewayForegroundService.saveAutoFailover(true)
                "✅ 已开启故障转移"
            }
            is ToolAction.DisableAutoFailover -> {
                GatewayForegroundService.saveAutoFailover(false)
                "✅ 已关闭故障转移"
            }
            is ToolAction.ToggleAutoFailover -> {
                val new = !GatewayForegroundService.getAutoFailover()
                GatewayForegroundService.saveAutoFailover(new)
                "✅ 故障转移已${if (new) "开启" else "关闭"}"
            }
            is ToolAction.EnableQtaiSj -> {
                GatewayForegroundService.saveQtaiSjEnabled(true)
                "✅ 已开启qtai-sj"
            }
            is ToolAction.DisableQtaiSj -> {
                GatewayForegroundService.saveQtaiSjEnabled(false)
                "✅ 已关闭qtai-sj"
            }
            is ToolAction.ToggleQtaiSj -> {
                val new = !GatewayForegroundService.getQtaiSjEnabled()
                GatewayForegroundService.saveQtaiSjEnabled(new)
                "✅ qtai-sj已${if (new) "开启" else "关闭"}"
            }
            is ToolAction.StartGateway -> {
                viewModel?.startGateway()
                "✅ 网关已启动"
            }
            is ToolAction.StopGateway -> {
                viewModel?.stopGateway()
                "✅ 网关已停止"
            }
            is ToolAction.ToggleGateway -> {
                if (GatewayForegroundService.isServiceRunning) {
                    viewModel?.stopGateway()
                    "✅ 网关已停止"
                } else {
                    viewModel?.startGateway()
                    "✅ 网关已启动"
                }
            }
            is ToolAction.ClearForcedModel -> {
                GatewayForegroundService.saveForcedModel("")
                "✅ 已清除强制模型"
            }
            is ToolAction.EnableProxy -> {
                GatewayForegroundService.saveProxyEnabled(true)
                "✅ 已开启代理"
            }
            is ToolAction.DisableProxy -> {
                GatewayForegroundService.saveProxyEnabled(false)
                "✅ 已关闭代理"
            }
            is ToolAction.ClearTokenUsage -> {
                viewModel?.clearAllUsage()
                "✅ 已清理统计"
            }
            is ToolAction.QueryStatus -> {
                val running = GatewayForegroundService.isServiceRunning
                val port = GatewayForegroundService.getGatewayPort()
                val failover = GatewayForegroundService.getAutoFailover()
                val qtaiSj = GatewayForegroundService.getQtaiSjEnabled()
                "📊 网关状态：${if (running) "运行中" else "已停止"} | 端口: $port | 故障转移: ${if (failover) "开" else "关"} | qtai-sj: ${if (qtaiSj) "开" else "关"}"
            }
            is ToolAction.QueryRanking -> {
                if (GatewayScheduler.pipelineSortedModelIds.isEmpty()) {
                    "⚠️ 暂无测速数据，请先启动测速"
                } else {
                    val list = GatewayScheduler.pipelineSortedModelIds.mapIndexed { i, id -> "  ${i+1}. $id" }.joinToString("\n")
                    "📈 测速排行（共${GatewayScheduler.pipelineSortedModelIds.size}个）：\n$list"
                }
            }
            is ToolAction.QueryCurrentModel -> {
                val forced = GatewayForegroundService.getForcedModel()
                if (forced.isNotBlank()) "🧠 当前在使用: $forced"
                else if (GatewayScheduler.pipelineSortedModelIds.isNotEmpty()) "🧠 当前在使用: ${GatewayScheduler.pipelineSortedModelIds.first()}"
                else "⚠️ 暂无模型数据"
            }
            is ToolAction.QueryTokenUsage -> {
                val up = GatewayForegroundService.trafficUploadBytes.get()
                val down = GatewayForegroundService.trafficDownloadBytes.get()
                "📊 会话流量 ↑${formatBytes(up)} ↓${formatBytes(down)}"
            }
            is ToolAction.EnableGroupChat -> {
                com.qtwl.gateway.service.GroupChatManager.setEnabled(true)
                "✅ 已开启群聊模式"
            }
            is ToolAction.DisableGroupChat -> {
                com.qtwl.gateway.service.GroupChatManager.setEnabled(false)
                "✅ 已关闭群聊模式"
            }
            is ToolAction.EnableDebug -> {
                GatewayForegroundService.saveDebugMode(true)
                "✅ 已开启调试模式"
            }
            is ToolAction.DisableDebug -> {
                GatewayForegroundService.saveDebugMode(false)
                "✅ 已关闭调试模式"
            }
        }
    }

    // ==================== 私有工具 ====================

    private fun switchModel(direction: Int): String {
        if (GatewayScheduler.pipelineSortedModelIds.isEmpty()) return ""
        val currentIdx = GatewayForegroundService.getGatewayConfig("current_model_idx", "0").toIntOrNull() ?: 0
        val nextIdx = (currentIdx + direction + GatewayScheduler.pipelineSortedModelIds.size) % GatewayScheduler.pipelineSortedModelIds.size
        GatewayForegroundService.saveGatewayConfig("current_model_idx", nextIdx.toString())
        GatewayForegroundService.saveForcedModel(GatewayScheduler.pipelineSortedModelIds[nextIdx])
        return GatewayScheduler.pipelineSortedModelIds[nextIdx]
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "%.1fMB".format(bytes.toDouble() / (1024 * 1024))
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
    object ToggleGateway : ToolAction()
    object ClearForcedModel : ToolAction()
    object EnableProxy : ToolAction()
    object DisableProxy : ToolAction()
    object ClearTokenUsage : ToolAction()
    object QueryStatus : ToolAction()
    object QueryRanking : ToolAction()
    object QueryCurrentModel : ToolAction()
    object QueryTokenUsage : ToolAction()
    object EnableGroupChat : ToolAction()
    object DisableGroupChat : ToolAction()
    object EnableDebug : ToolAction()
    object DisableDebug : ToolAction()
}