package com.qtwl.gateway.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationManagerTest {
    @Test
    fun literalLocalizationUsesTheSelectedLanguageAndEnglishFallback() {
        withLanguage(AppLanguage.ZH_CN) {
            assertEquals("简体", localizedText("简体", "English", "繁體"))
        }
        withLanguage(AppLanguage.ZH_TW) {
            assertEquals("繁體", localizedText("简体", "English", "繁體"))
            assertEquals("English", localizedText("简体", "English"))
        }
        withLanguage(AppLanguage.EN) {
            assertEquals("English", localizedText("简体", "English", "繁體"))
        }
        withLanguage(AppLanguage.JA) {
            assertEquals("English", localizedText("简体", "English", "繁體"))
        }
    }

    @Test
    fun runtimeLocalizationChangesAppGeneratedStatusesInBothDirections() {
        withLanguage(AppLanguage.EN) {
            assertEquals(
                "✅ Gateway port set to 8889",
                localizeRuntimeText("✅ 网关端口已设置为 8889"),
            )
        }
        withLanguage(AppLanguage.ZH_CN) {
            assertEquals(
                "✅ 网关端口已设置为 8889",
                localizeRuntimeText("✅ Gateway port set to 8889"),
            )
        }
    }

    @Test
    fun generatedDefaultsAreLocalizedWithoutChangingUserContent() {
        withLanguage(AppLanguage.EN) {
            assertEquals("New chat", localizeGeneratedName("新对话"))
            assertEquals("My custom chat", localizeGeneratedName("My custom chat"))
        }
    }

    private fun withLanguage(language: AppLanguage, assertion: () -> Unit) {
        val field = TranslationManager::class.java.getDeclaredField("currentLanguage")
        field.isAccessible = true
        val previous = field.get(null) as AppLanguage
        try {
            field.set(null, language)
            assertion()
        } finally {
            field.set(null, previous)
        }
    }
}
