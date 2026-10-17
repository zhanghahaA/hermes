package com.hermes.mobile.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

// app/src/main/java/com/hermes/mobile/platform/Platform.kt 的桌面替身

val supportsDynamicColor: Boolean get() = true

@Suppress("UNUSED_PARAMETER")
@Composable
fun dynamicColorScheme(dark: Boolean): ColorScheme? = null
