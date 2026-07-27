package com.qtwl.gateway.data.model

/**
 * Provider-scoped identity for a model.
 *
 * A model ID is only unique inside one provider. Persisted selections and
 * ranking entries must therefore use providerId + modelId rather than modelId
 * alone. Legacy modelId-only values are accepted only when they resolve to one
 * unambiguous enabled model.
 */
data class ModelRouteKey(
    val providerId: Long,
    val modelId: String
) {
    val value: String
        get() = encode(providerId, modelId)

    val displayValue: String
        get() = "P$providerId · $modelId"

    companion object {
        const val SEPARATOR = "::"

        fun encode(providerId: Long, modelId: String): String =
            if (providerId > 0L) "$providerId$SEPARATOR$modelId" else modelId

        fun parse(raw: String?): ModelRouteKey? {
            val value = raw?.trim().orEmpty()
            val separatorIndex = value.indexOf(SEPARATOR)
            if (separatorIndex <= 0) return null

            val providerId = value.substring(0, separatorIndex).toLongOrNull() ?: return null
            val modelId = value.substring(separatorIndex + SEPARATOR.length)
            if (providerId <= 0L || modelId.isBlank()) return null
            return ModelRouteKey(providerId, modelId)
        }

        fun modelIdOf(raw: String): String = parse(raw)?.modelId ?: raw

        fun providerIdOf(raw: String): Long? = parse(raw)?.providerId

        fun display(raw: String): String = parse(raw)?.displayValue ?: raw
    }
}

val AiModel.routeKey: String
    get() = ModelRouteKey.encode(providerId, modelId)

fun AiModel.matchesRouteKey(raw: String): Boolean {
    val parsed = ModelRouteKey.parse(raw)
    return if (parsed != null) {
        providerId == parsed.providerId && modelId == parsed.modelId
    } else {
        modelId == raw
    }
}

/**
 * Resolve a provider-scoped key. A legacy modelId-only value is resolved only
 * when exactly one model has that ID, because picking the first duplicate would
 * silently route to the wrong provider.
 */
fun Iterable<AiModel>.findByRouteKey(raw: String?): AiModel? {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return null

    val parsed = ModelRouteKey.parse(value)
    if (parsed != null) {
        return firstOrNull { it.providerId == parsed.providerId && it.modelId == parsed.modelId }
    }

    val legacyMatches = filter { it.modelId == value }
    return legacyMatches.singleOrNull()
}

fun Iterable<AiModel>.orderedByRouteKeys(keys: Iterable<String>): List<AiModel> {
    val source = toList()
    val seen = mutableSetOf<String>()
    return keys.mapNotNull { key ->
        source.findByRouteKey(key)?.takeIf { seen.add(it.routeKey) }
    }
}