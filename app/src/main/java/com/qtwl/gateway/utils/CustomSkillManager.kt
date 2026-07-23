package com.qtwl.gateway.utils

import com.qtwl.gateway.service.GatewayForegroundService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString

/**
 * 自定义技能管理器
 * 支持手动添加、Git导入、编辑、删除自定义技能
 */
object CustomSkillManager {

    private const val PREF_KEY = "custom_skills_json"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    data class CustomSkill(
        val id: String = "",           // 唯一ID（自动生成）
        val name: String,              // 技能名称
        val description: String,        // 技能描述
        val keywords: String,           // 触发关键词（逗号分隔）
        val prompt: String,             // 执行提示词
        val source: String = "manual"   // 来源: manual / git_import
    )

    /** 获取所有自定义技能 */
    fun getAll(): List<CustomSkill> {
        val raw = GatewayForegroundService.getGatewayConfig(PREF_KEY, "[]")
        return try {
            val arr = json.parseToJsonElement(raw).jsonArray
            arr.map { elem ->
                val obj = elem.jsonObject
                CustomSkill(
                    id = obj["id"]?.jsonPrimitive?.content ?: "",
                    name = obj["name"]?.jsonPrimitive?.content ?: "",
                    description = obj["description"]?.jsonPrimitive?.content ?: "",
                    keywords = obj["keywords"]?.jsonPrimitive?.content ?: "",
                    prompt = obj["prompt"]?.jsonPrimitive?.content ?: "",
                    source = obj["source"]?.jsonPrimitive?.content ?: "manual"
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /** 保存所有自定义技能 */
    private fun saveAll(skills: List<CustomSkill>) {
        val arr = json.encodeToString(skills.map { skill ->
            buildJsonObject {
                put("id", JsonPrimitive(skill.id))
                put("name", JsonPrimitive(skill.name))
                put("description", JsonPrimitive(skill.description))
                put("keywords", JsonPrimitive(skill.keywords))
                put("prompt", JsonPrimitive(skill.prompt))
                put("source", JsonPrimitive(skill.source))
            }
        })
        GatewayForegroundService.saveGatewayConfig(PREF_KEY, arr)
    }

    /** 添加自定义技能 */
    fun add(skill: CustomSkill): CustomSkill {
        val skills = getAll().toMutableList()
        val newSkill = skill.copy(id = "custom_${System.currentTimeMillis()}")
        skills.add(newSkill)
        saveAll(skills)
        return newSkill
    }

    /** 更新技能 */
    fun update(skill: CustomSkill) {
        val skills = getAll().toMutableList()
        val idx = skills.indexOfFirst { it.id == skill.id }
        if (idx >= 0) {
            skills[idx] = skill
            saveAll(skills)
        }
    }

    /** 删除技能 */
    fun delete(id: String) {
        val skills = getAll().toMutableList()
        skills.removeAll { it.id == id }
        saveAll(skills)
    }

    /** 从GitHub导入技能（从仓库读取skills.json） */
    fun importFromGit(gitUrl: String): Result<List<CustomSkill>> {
        return try {
            // 支持 raw.githubusercontent.com URL
            val url = when {
                gitUrl.contains("raw.githubusercontent.com") -> gitUrl
                gitUrl.contains("github.com") -> gitUrl
                    .replace("github.com", "raw.githubusercontent.com")
                    .replace("/blob/", "/")
                else -> return Result.failure(Exception("不支持的Git地址，请使用raw.githubusercontent.com的raw链接"))
            }
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val arr = try { json.parseToJsonElement(body).jsonArray } catch (_: Exception) { return Result.failure(Exception("导入文件格式错误，需要JSON数组")) }
            val imported = arr.mapNotNull { elem ->
                try {
                    val obj = elem.jsonObject
                    CustomSkill(
                        name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        description = obj["description"]?.jsonPrimitive?.content ?: "",
                        keywords = obj["keywords"]?.jsonPrimitive?.content ?: "",
                        prompt = obj["prompt"]?.jsonPrimitive?.content ?: "",
                        source = "git_import"
                    )
                } catch (_: Exception) { null }
            }
            if (imported.isEmpty()) return Result.failure(Exception("未找到有效技能数据"))

            val existing = getAll().toMutableList()
            imported.forEach { skill ->
                existing.add(skill.copy(id = "custom_${System.currentTimeMillis()}_${existing.size}"))
            }
            saveAll(existing)
            Result.success(imported)
        } catch (e: Exception) {
            Result.failure(Exception("导入失败: ${e.message}"))
        }
    }
}