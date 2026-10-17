package com.hermes.mobile.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// 品牌色：靛蓝（Hermes 信使）+ 金色点缀。按 Material 3 色调体系手工调校。

val HermesLightColors = lightColorScheme(
    primary = Color(0xFF4C56AF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDFE0FF),
    onPrimaryContainer = Color(0xFF343D96),
    secondary = Color(0xFF5B5D72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E1F9),
    onSecondaryContainer = Color(0xFF434559),
    tertiary = Color(0xFF7A5900),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF5C4200),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC7C5D0),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF303036),
    inverseOnSurface = Color(0xFFF2EFF7),
    inversePrimary = Color(0xFFBDC2FF),
    surfaceDim = Color(0xFFDBD9E0),
    surfaceBright = Color(0xFFFBF8FF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE4E1E9),
)

val HermesDarkColors = darkColorScheme(
    primary = Color(0xFFBDC2FF),
    onPrimary = Color(0xFF1B247F),
    primaryContainer = Color(0xFF343D96),
    onPrimaryContainer = Color(0xFFDFE0FF),
    secondary = Color(0xFFC4C5DD),
    onSecondary = Color(0xFF2D2F42),
    secondaryContainer = Color(0xFF434559),
    onSecondaryContainer = Color(0xFFE0E1F9),
    tertiary = Color(0xFFEFC070),
    onTertiary = Color(0xFF412D00),
    tertiaryContainer = Color(0xFF5C4200),
    onTertiaryContainer = Color(0xFFFFDEA6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF918F9A),
    outlineVariant = Color(0xFF46464F),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE4E1E9),
    inverseOnSurface = Color(0xFF303036),
    inversePrimary = Color(0xFF4C56AF),
    surfaceDim = Color(0xFF121318),
    surfaceBright = Color(0xFF39393F),
    surfaceContainerLowest = Color(0xFF0D0E13),
    surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF2A292F),
    surfaceContainerHighest = Color(0xFF35343A),
)

/** Material 3 配色之外，聊天界面需要的几种语义色。 */
@Immutable
data class HermesExtendedColors(
    val online: Color,
    val warning: Color,
    val userBubble: Color,
    val onUserBubble: Color,
    val assistantBubble: Color,
    val onAssistantBubble: Color,
    val codeBackground: Color,
)

val LightExtendedColors = HermesExtendedColors(
    online = Color(0xFF1E8E3E),
    warning = Color(0xFFB26A00),
    userBubble = Color(0xFF4C56AF),
    onUserBubble = Color(0xFFFFFFFF),
    assistantBubble = Color(0xFFEFEDF4),
    onAssistantBubble = Color(0xFF1B1B21),
    codeBackground = Color(0xFFE4E1E9),
)

val DarkExtendedColors = HermesExtendedColors(
    online = Color(0xFF6DD58C),
    warning = Color(0xFFFFB95C),
    userBubble = Color(0xFF3C46A0),
    onUserBubble = Color(0xFFF2F1FF),
    assistantBubble = Color(0xFF2A292F),
    onAssistantBubble = Color(0xFFE4E1E9),
    codeBackground = Color(0xFF1B1B21),
)

val LocalHermesColors = staticCompositionLocalOf { LightExtendedColors }
