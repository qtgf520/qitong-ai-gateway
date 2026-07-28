package com.qtwl.gateway.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 自定义路由规则实体 —— 根据请求特征决定转发目标
 * 匹配顺序：priority 数值越小优先级越高
 */
@Entity(
    tableName = "routing_rule",
    indices = [Index("priority"), Index("enabled")]
)
@Serializable
data class RoutingRule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,  // 规则名称（如"图片请求走视觉模型"）
    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,
    @ColumnInfo(name = "priority")
    val priority: Int = 0,  // 优先级，数字越小越先匹配
    // 匹配条件（空=不匹配该条件）
    @ColumnInfo(name = "path_pattern")
    val pathPattern: String = "",  // 路径匹配，如 "/v1/chat/completions"
    @ColumnInfo(name = "model_pattern")
    val modelPattern: String = "",  // 模型名匹配，支持*通配符，如 "gpt-*"
    @ColumnInfo(name = "api_key_pattern")
    val apiKeyPattern: String = "",  // API密钥前缀匹配
    @ColumnInfo(name = "provider_id")
    val providerId: Long? = null,  // 指定服务商ID（null=不限）
    // 匹配后动作
    @ColumnInfo(name = "target_model_key")
    val targetModelKey: String = "",  // 目标模型 routeKey
    @ColumnInfo(name = "action")
    val action: String = "route",  // route | block | redirect
    @ColumnInfo(name = "block_message")
    val blockMessage: String = "",  // block时的提示信息
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
