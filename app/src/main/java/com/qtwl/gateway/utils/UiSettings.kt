package com.qtwl.gateway.utils

import android.content.Context

/**
 * 界面/手感开关。刻意做成极简的 SharedPreferences 包装，
 * 不依赖 ViewModel，改完立刻生效（下次组合读数即可）。
 */
object UiSettings {

    @Volatile
    private var app: Context? = null

    fun attach(context: Context) {
        if (app == null) app = context.applicationContext
    }

    private fun prefs(): android.content.SharedPreferences? =
        app?.getSharedPreferences("ui_settings", Context.MODE_PRIVATE)

    /** 底栏拉伸：按住胶囊左右甩时是否做「胶体拉伸 + 越界回弹」 */
    var navStretch: Boolean
        get() = prefs()?.getBoolean("nav_stretch", true) ?: true
        set(v) {
            prefs()?.edit()?.putBoolean("nav_stretch", v)?.apply()
        }

    /** 全局液态玻璃：卡片/输入栏是否用玻璃质感 */
    var glassSurfaces: Boolean
        get() = prefs()?.getBoolean("glass_surfaces", true) ?: true
        set(v) {
            prefs()?.edit()?.putBoolean("glass_surfaces", v)?.apply()
        }
}
