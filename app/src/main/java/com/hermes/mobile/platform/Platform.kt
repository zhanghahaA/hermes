package com.hermes.mobile.platform

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

/**
 * 系统返回（手势或返回键）。[onBack] 收到的 Flow 是手势进度 0..1：
 * 支持预测性返回的系统会边拖边发进度；不支持的系统在松手时直接结束 Flow。
 * Flow 被取消表示用户放弃了返回。
 */
@Composable
fun SystemBackHandler(enabled: Boolean, onBack: suspend (progress: Flow<Float>) -> Unit) {
    PredictiveBackHandler(enabled) { events -> onBack(events.map { it.progress }) }
}
