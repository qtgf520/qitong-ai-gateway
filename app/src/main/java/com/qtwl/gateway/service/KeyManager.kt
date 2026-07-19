package com.qtwl.gateway.service

import com.qtwl.gateway.GatewayApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * API Key 管理器 — 管理访问密钥及其权限
 * 本地请求（localhost/127.0.0.1）默认免密钥
 * 每把密钥可单独控制：可用模型、qtai-sj 访问权限
 */
@Serializable
data class ApiKeyEntry(
    val key: String,                    // 密钥字符串
    val label: String = "",             // 标签/备注
    val enabled: Boolean = true,        // 是否启用
    val allowedModels: List<String> = emptyList(),  // 允许访问的模型ID列表（空=全部）
    val qtaiSjAccess: Boolean = true,   // 是否允许访问 qtai-sj
    val createdAt: Long = System.currentTimeMillis()
)

object KeyManager {
    private const val PREF_KEY = "api_key_entries"
    private val json = Json { ignoreUnknownKeys = true }
    
    /** 获取所有密钥 */
    fun getAllKeys(): List<ApiKeyEntry> {
        val str = GatewayForegroundService.getGatewayConfig(PREF_KEY, "[]")
        if (str.isBlank()) return emptyList()
        return try {
            json.decodeFromString<List<ApiKeyEntry>>(str)
        } catch (_: Exception) { emptyList() }
    }
    
    /** 保存密钥列表 */
    private fun saveAllKeys(keys: List<ApiKeyEntry>) {
        GatewayForegroundService.saveGatewayConfig(PREF_KEY, json.encodeToString(keys))
    }
    
    /** 添加密钥 */
    fun addKey(key: String, label: String = "", allowedModels: List<String> = emptyList(), qtaiSjAccess: Boolean = true): Boolean {
        val keys = getAllKeys().toMutableList()
        if (keys.any { it.key == key }) return false // 已存在
        keys.add(ApiKeyEntry(key = key, label = label, allowedModels = allowedModels, qtaiSjAccess = qtaiSjAccess))
        saveAllKeys(keys)
        // 同步到旧式密钥列表
        syncToLegacyAllowedKeys()
        return true
    }
    
    /** 删除密钥 */
    fun deleteKey(key: String): Boolean {
        val keys = getAllKeys().toMutableList()
        val removed = keys.removeAll { it.key == key }
        if (removed) {
            saveAllKeys(keys)
            syncToLegacyAllowedKeys()
        }
        return removed
    }
    
    /** 更新密钥 */
    fun updateKey(key: String, label: String? = null, enabled: Boolean? = null, allowedModels: List<String>? = null, qtaiSjAccess: Boolean? = null): Boolean {
        val keys = getAllKeys().toMutableList()
        val idx = keys.indexOfFirst { it.key == key }
        if (idx < 0) return false
        val old = keys[idx]
        keys[idx] = old.copy(
            label = label ?: old.label,
            enabled = enabled ?: old.enabled,
            allowedModels = allowedModels ?: old.allowedModels,
            qtaiSjAccess = qtaiSjAccess ?: old.qtaiSjAccess
        )
        saveAllKeys(keys)
        syncToLegacyAllowedKeys()
        return true
    }
    
    /** 验证密钥是否有效 */
    fun validateKey(key: String): ApiKeyEntry? {
        return getAllKeys().find { it.key == key && it.enabled }
    }
    
    /** 检查密钥是否有权访问模型 */
    fun canAccessModel(key: String, modelId: String): Boolean {
        val entry = validateKey(key) ?: return false
        if (entry.allowedModels.isEmpty()) return true // 空=全部
        return entry.allowedModels.contains(modelId)
    }
    
    /** 检查密钥是否有权访问 qtai-sj */
    fun canAccessQtaiSj(key: String): Boolean {
        return validateKey(key)?.qtaiSjAccess ?: false
    }
    
    /** 清空所有密钥 */
    fun clearAllKeys() {
        saveAllKeys(emptyList())
        syncToLegacyAllowedKeys()
    }
    
    /** 同步到旧式 allowed_api_keys 配置（兼容） */
    private fun syncToLegacyAllowedKeys() {
        val allKeys = getAllKeys().filter { it.enabled }.map { it.key }.toSet()
        GatewayForegroundService.saveAllowedApiKeys(allKeys)
    }
    
    /** 判断是否为本地请求（免密钥） */
    fun isLocalRequest(ip: String): Boolean {
        return ip == "localhost" || ip == "127.0.0.1" || ip == "::1" || ip == "0.0.0.0" || ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.16.")
    }
}