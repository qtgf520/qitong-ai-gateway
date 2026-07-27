package com.qtwl.gateway.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelRouteKeyTest {
    private fun model(id: Long, providerId: Long, modelId: String) = AiModel(
        id = id,
        providerId = providerId,
        modelId = modelId,
        displayName = modelId
    )

    @Test
    fun duplicateModelNamesHaveDifferentProviderScopedKeys() {
        val first = model(35, 7, "Haven")
        val second = model(36, 9, "Haven")

        assertNotEquals(first.routeKey, second.routeKey)
        assertEquals(first, listOf(first, second).findByRouteKey(first.routeKey))
        assertEquals(second, listOf(first, second).findByRouteKey(second.routeKey))
    }

    @Test
    fun ambiguousLegacyModelIdIsNotGuessed() {
        val models = listOf(model(35, 7, "Haven"), model(36, 9, "Haven"))
        assertNull(models.findByRouteKey("Haven"))
    }

    @Test
    fun uniqueLegacyModelIdStillMigrates() {
        val only = model(12, 3, "unique-model")
        assertEquals(only, listOf(only).findByRouteKey("unique-model"))
    }

    @Test
    fun rankingOrderKeepsDuplicateNamesAsSeparateRows() {
        val first = model(35, 7, "Haven")
        val second = model(36, 9, "Haven")
        val ordered = listOf(first, second).orderedByRouteKeys(
            listOf(second.routeKey, first.routeKey)
        )

        assertEquals(listOf(second, first), ordered)
    }
}