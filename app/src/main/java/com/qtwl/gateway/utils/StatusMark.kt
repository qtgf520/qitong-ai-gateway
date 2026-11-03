package com.qtwl.gateway.utils

/**
 * 流水线测速状态标记（纯文本，不含 emoji）
 *
 * 设计目的：
 * 1. 彻底移除 emoji，同时保留可机读的状态语义
 * 2. UI 层用 [strip] 剥离标记后，配合矢量图标渲染
 * 3. 逻辑层用 [isDone] / [isOk] / [isFail] 判断，避免脆弱的 emoji 字符串比较
 *
 * 用法：
 *   赋值：status = PipelineStatusMark.OK + "TTFT=120ms TPS=45 900ms"
 *   判断：PipelineStatusMark.isOk(item.status)
 *   显示：Text(PipelineStatusMark.strip(item.status))
 */
object PipelineStatusMark {

    /** 测速成功 */
    const val OK = "[OK]"

    /** 测速失败 */
    const val FAIL = "[FAIL]"

    /** 等待测速 */
    const val WAIT = "[WAIT]"

    /** 正在测速 */
    const val TEST = "[TEST]"

    /** 是否已有结果（成功或失败） */
    fun isDone(status: String): Boolean = status.startsWith(OK) || status.startsWith(FAIL)

    fun isOk(status: String): Boolean = status.startsWith(OK)

    fun isFail(status: String): Boolean = status.startsWith(FAIL)

    fun isWait(status: String): Boolean = status.startsWith(WAIT)

    fun isTesting(status: String): Boolean = status.startsWith(TEST)

    /** 剥离标记，返回可展示的纯文本 */
    fun strip(status: String): String = when {
        status.startsWith(OK) -> status.removePrefix(OK).trim()
        status.startsWith(FAIL) -> status.removePrefix(FAIL).trim()
        status.startsWith(WAIT) -> status.removePrefix(WAIT).trim()
        status.startsWith(TEST) -> status.removePrefix(TEST).trim()
        else -> status
    }
}

/**
 * 一次性提示消息标记（snackbar / syncResult）
 * 纯文本前缀，便于 UI 判断成功/失败/警告配色，展示时剥离
 */
object MessageMark {
    const val OK = "成功: "
    const val FAIL = "失败: "
    const val WARN = "警告: "

    fun isOk(msg: String): Boolean = msg.startsWith(OK)

    fun isFail(msg: String): Boolean = msg.startsWith(FAIL)

    fun isWarn(msg: String): Boolean = msg.startsWith(WARN)

    /** 剥离标记 */
    fun strip(msg: String): String = when {
        msg.startsWith(OK) -> msg.removePrefix(OK)
        msg.startsWith(FAIL) -> msg.removePrefix(FAIL)
        msg.startsWith(WARN) -> msg.removePrefix(WARN)
        else -> msg
    }
}