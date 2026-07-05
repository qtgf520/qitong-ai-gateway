package com.qtwl.gateway.utils

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * 语言管理唯一入口 —— AppCompatDelegate 官方方案
 * 替代旧 TranslationManager 的语言切换部分
 */
object LocaleHelper {
    private const val PREFS_NAME = "app_locale_prefs"
    private const val KEY_LANGUAGE = "language"

    /** 应用语言（保存 + 通知系统） */
    fun applyLocale(app: Application, languageCode: String) {
        val prefs = app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, languageCode).apply()
        val locale = when (languageCode) {
            "system" -> LocaleListCompat.getEmptyLocaleList()
            else -> LocaleListCompat.forLanguageTags(languageCode)
        }
        AppCompatDelegate.setApplicationLocales(locale)
    }

    /** 获取已保存的语言 */
    fun getSavedLocale(app: Application): String {
        val prefs = app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE, "system") ?: "system"
    }
}