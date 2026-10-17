package com.hermes.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.model.ThemeMode
import com.hermes.mobile.platform.dynamicColorScheme

val HermesShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun HermesTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val colors = (if (dynamicColor) dynamicColorScheme(dark) else null)
        ?: if (dark) HermesDarkColors else HermesLightColors
    val extended = (if (dark) DarkExtendedColors else LightExtendedColors).let {
        // 动态取色时让气泡跟随系统主色
        if (dynamicColor) it.copy(userBubble = colors.primary, onUserBubble = colors.onPrimary, assistantBubble = colors.surfaceContainerHigh) else it
    }

    CompositionLocalProvider(LocalHermesColors provides extended) {
        MaterialTheme(
            colorScheme = colors,
            typography = HermesTypography,
            shapes = HermesShapes,
            content = content,
        )
    }
}

/** 给头像、标签生成稳定的颜色。 */
fun avatarColor(seed: String, dark: Boolean): Color {
    val palette = if (dark) {
        listOf(0xFF5C64C8, 0xFF2E7D6B, 0xFF9A5B2E, 0xFF8E4A8A, 0xFF3D7AB8, 0xFF7A6A1E)
    } else {
        listOf(0xFF6871D9, 0xFF2F9C84, 0xFFC27838, 0xFFA45AA0, 0xFF4A8FD6, 0xFF9C8722)
    }
    return Color(palette[(seed.hashCode() and 0x7fffffff) % palette.size])
}
