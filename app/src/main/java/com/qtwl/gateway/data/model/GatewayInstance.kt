package com.qtwl.gateway.data.model

/**
 * 网关实例数据模型
 * 支持多实例运行（个人/办公/开发等）
 */
data class GatewayInstance(
    val name: String,
    val port: Int,
    val enabled: Boolean = false,
    val modelMap: Map<String, String> = emptyMap(), // 模型别名映射
    val replaceRules: List<ReplaceRule> = emptyList(), // 请求/响应替换规则
    val logServer: String? = null, // 远程日志上报地址
    val adminKey: String? = null // 管理密钥
) {
    val instanceKey: String get() = "instance_$name"
    
    companion object {
        const val DEFAULT_PORT = 8889
        fun defaultInstance(name: String = "default") = GatewayInstance(
            name = name,
            port = DEFAULT_PORT,
            enabled = false
        )
    }
}

/**
 * 请求/响应替换规则
 * 对应竞品的 replace.rules 功能
 */
data class ReplaceRule(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val pattern: String, // 正则表达式
    val replacement: String,
    val caseSensitive: Boolean = false,
    val applyTo: String // "request", "response", "both"
)