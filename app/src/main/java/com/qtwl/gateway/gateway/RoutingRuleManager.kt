package com.qtwl.gateway.gateway

import com.qtwl.gateway.data.db.AppDatabase
import com.qtwl.gateway.data.model.RoutingRule
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 自定义路由规则引擎 —— 根据请求特征匹配规则，决定转发目标
 *
 * 匹配顺序：priority 数字越小优先级越高
 * 匹配条件（AND 关系）：path + model + apiKey + provider
 * 动作：route（转发到指定模型）| block（拒绝请求）
 */
object RoutingRuleManager {
    private val mutex = Mutex()
    private var cachedRules: List<RoutingRule>? = null
    private var cacheTime: Long = 0
    private const val CACHE_TTL = 30_000L  // 缓存30秒

    /**
     * 加载所有启用的规则（带缓存）
     */
    suspend fun getEnabledRules(database: AppDatabase): List<RoutingRule> {
        val now = System.currentTimeMillis()
        val cached = cachedRules
        if (cached != null && now - cacheTime < CACHE_TTL) return cached
        return mutex.withLock {
            val rules = database.routingRuleDao().getEnabledRules()
            cachedRules = rules
            cacheTime = now
            rules
        }
    }

    /**
     * 清除缓存（规则变更时调用）
     */
    fun invalidateCache() {
        cachedRules = null
        cacheTime = 0
    }

    /**
     * 匹配规则 —— 返回第一个匹配的规则，无匹配返回 null
     *
     * @param path      请求路径，如 "/v1/chat/completions"
     * @param modelId   请求的模型ID
     * @param apiKey    API密钥（完整密钥）
     * @param providerId 服务商ID
     */
    suspend fun matchRule(
        database: AppDatabase,
        path: String,
        modelId: String,
        apiKey: String,
        providerId: Long
    ): RoutingRule? {
        val rules = getEnabledRules(database)
        for (rule in rules) {
            if (matchSingleRule(rule, path, modelId, apiKey, providerId)) {
                return rule
            }
        }
        return null
    }

    /**
     * 判断单条规则是否匹配
     */
    private fun matchSingleRule(
        rule: RoutingRule,
        path: String,
        modelId: String,
        apiKey: String,
        providerId: Long
    ): Boolean {
        // 路径匹配（空=不匹配）
        if (rule.pathPattern.isNotBlank() && !path.contains(rule.pathPattern, ignoreCase = true)) {
            return false
        }
        // 模型名匹配（支持 * 通配符）
        if (rule.modelPattern.isNotBlank()) {
            if (!matchWildcard(modelId, rule.modelPattern)) return false
        }
        // API密钥前缀匹配
        if (rule.apiKeyPattern.isNotBlank() && !apiKey.startsWith(rule.apiKeyPattern)) {
            return false
        }
        // 服务商匹配
        if (rule.providerId != null && rule.providerId != providerId) {
            return false
        }
        return true
    }

    /**
     * 简单通配符匹配：* 匹配任意字符
     */
    private fun matchWildcard(text: String, pattern: String): Boolean {
        if (pattern == "*") return true
        if (!pattern.contains("*")) return text.equals(pattern, ignoreCase = true)
        // 转义正则，* → .*
        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .toRegex(RegexOption.IGNORE_CASE)
        return regex.matches(text)
    }
}