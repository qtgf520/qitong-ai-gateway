package com.qtwl.gateway.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject

/**
 * Agent 工具定义与执行结果
 *
 * 一个 Tool 由三部分组成：
 *  1. 元数据（name / description / 参数 JSON Schema）→ 发给模型让它知道能做什么
 *  2. 参数声明 → 供 UI 展示与参数校验
 *  3. execute() → 真正执行并返回文本结果给模型
 */
data class AgentTool(
    val name: String,
    val description: String,
    val parameters: List<ToolParam> = emptyList(),
    val requiresConfirm: Boolean = false,
    val executor: suspend (Map<String, String>) -> ToolResult
) {
    /** 转成 OpenAI function calling 格式 */
    fun toOpenAiSchema(): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put("description", description)
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    parameters.forEach { p ->
                        putJsonObject(p.name) {
                            put("type", p.type)
                            put("description", p.description)
                            if (p.enumValues.isNotEmpty()) {
                                putJsonArray("enum") {
                                    p.enumValues.forEach { add(JsonPrimitive(it)) }
                                }
                            }
                        }
                    }
                }
                if (parameters.any { it.required }) {
                    putJsonArray("required") {
                        parameters.filter { it.required }.forEach { add(JsonPrimitive(it.name)) }
                    }
                }
            }
        }
    }
}

/** 工具参数声明 */
data class ToolParam(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
    val enumValues: List<String> = emptyList()
)

/** 工具执行结果 */
data class ToolResult(
    val success: Boolean,
    val content: String
) {
    companion object {
        fun ok(text: String) = ToolResult(true, text)
        fun fail(text: String) = ToolResult(false, text)
    }
}

/** 一次工具调用记录（供 UI 展示） */
data class ToolCallRecord(
    val id: String,
    val toolName: String,
    val arguments: String,
    val result: String? = null,
    val success: Boolean = true,
    val round: Int = 0
)
