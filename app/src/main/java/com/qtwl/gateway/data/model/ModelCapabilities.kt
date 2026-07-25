package com.qtwl.gateway.data.model

import kotlinx.serialization.Serializable

/**
 * 模型能力标签 — 9大维度
 * 按行业共识定义：tool_call / vision / thinking / audio_in / audio_out / video / image_gen / embeddings / realtime
 */
@Serializable
data class ModelCapabilities(
    val toolCall: Boolean = false,
    val vision: Boolean = false,
    val thinking: Boolean = false,
    val audioIn: Boolean = false,
    val audioOut: Boolean = false,
    val video: Boolean = false,
    val imageGen: Boolean = false,
    val embeddings: Boolean = false,
    val realtime: Boolean = false,
) {
    /** 转成 UI 标签列表，按固定顺序 */
    fun toTags(): List<CapabilityTag> {
        val list = mutableListOf<CapabilityTag>()
        if (toolCall)   list += CapabilityTag("tool_call",  "工具调用",  "🔧")
        if (vision)     list += CapabilityTag("vision",     "视觉理解",  "👁️")
        if (thinking)   list += CapabilityTag("thinking",   "深度思考",  "🧠")
        if (audioIn)    list += CapabilityTag("audio_in",   "语音输入",  "🎤")
        if (audioOut)   list += CapabilityTag("audio_out",  "语音输出",  "🔊")
        if (video)      list += CapabilityTag("video",      "视频",      "🎬")
        if (imageGen)   list += CapabilityTag("image_gen",  "图像生成",  "🎨")
        if (embeddings) list += CapabilityTag("embeddings", "向量嵌入",  "📐")
        if (realtime)   list += CapabilityTag("realtime",   "实时对话",  "⚡")
        return list
    }

    companion object {
        /** 从字符串列表快速构建 */
        fun fromKeys(keys: List<String>): ModelCapabilities = ModelCapabilities(
            toolCall   = "tool_call"   in keys,
            vision     = "vision"      in keys,
            thinking   = "thinking"    in keys,
            audioIn    = "audio_in"    in keys,
            audioOut   = "audio_out"   in keys,
            video      = "video"       in keys,
            imageGen   = "image_gen"   in keys,
            embeddings = "embeddings"  in keys,
            realtime   = "realtime"   in keys,
        )
    }
}

@Serializable
data class CapabilityTag(val key: String, val label: String, val icon: String)