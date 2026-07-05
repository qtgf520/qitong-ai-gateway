package com.qtwl.gateway.utils

import androidx.compose.runtime.compositionLocalOf
import java.util.Locale

/** CompositionLocal 传递当前语言，让 Compose 自动重组 */
val LocalAppLocale = compositionLocalOf { Locale.getDefault() }