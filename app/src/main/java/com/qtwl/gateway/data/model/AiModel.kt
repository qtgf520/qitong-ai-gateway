package com.qtwl.gateway.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(
    tableName = "models",
    foreignKeys = [
        ForeignKey(
            entity = Provider::class,
            parentColumns = ["id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("provider_id")]
)
@Serializable
data class AiModel(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "provider_id")
    val providerId: Long,
    @ColumnInfo(name = "model_id")
    val modelId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "is_default")
    val isDefault: Boolean = false,
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = "Pending",  // Synced / Pending / Failed
    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,       // 模型是否启用/暂停
    @ColumnInfo(name = "custom_alias")
    val customAlias: String = "",          // 自定义别名，空字符串表示使用默认显示名
    @ColumnInfo(name = "use_proxy")
    val useProxy: Boolean = true           // 是否走代理，true=走代理(默认)，false=直连
)