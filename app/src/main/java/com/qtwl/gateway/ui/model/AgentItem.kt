package com.qtwl.gateway.ui.model

/**
 * Agent 运行期间的流式时间线。
 *
 * 工具调用不再挤在输入框上方，而是和正文、思考一起按发生顺序往下追加，
 * 观感接近常见的编码类 Agent：想说就说一段，想调工具就冒一张卡片。
 */
sealed class AgentItem {
    abstract val id: String

    /** 模型输出的一段正文 */
    data class Speech(override val id: String, val text: String) : AgentItem()

    /** 思考内容（可展开/收起） */
    data class Think(override val id: String, val text: String) : AgentItem()

    /** 一次工具调用：一张卡片 */
    data class Tool(
        override val id: String,
        val name: String,
        val args: String,
        val result: String? = null,
        val ok: Boolean = false,
        val running: Boolean = true
    ) : AgentItem()
}
