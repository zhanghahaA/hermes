package com.hermes.mobile.platform

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// 少量依赖 Android 的能力集中放在这里，界面代码只调用这些函数。
// tools/ui-preview 里有同名的桌面版实现，用来在没有 Android SDK 的环境里渲染界面截图。

val supportsDynamicColor: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun dynamicColorScheme(dark: Boolean): ColorScheme? {
    if (!supportsDynamicColor) return null
    val context = LocalContext.current
    return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}
