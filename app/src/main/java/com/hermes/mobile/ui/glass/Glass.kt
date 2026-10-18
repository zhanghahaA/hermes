package com.hermes.mobile.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.shadow.Shadow

/**
 * 液态玻璃。
 *
 * 用法：内容区域加 `Modifier.layerBackdrop(backdrop)` 作为“被透过去看的东西”，
 * 玻璃控件和内容是兄弟节点（不能放在内容里面），用 [liquidGlass] 从 backdrop 取样。
 *
 * 效果分级：Android 13+ 有折射（lens）+ 模糊；Android 12 只有模糊；更早的系统退化成半透明底色。
 */
@Immutable
data class GlassStyle(
    /** 玻璃表面叠加的底色，决定玻璃的“明暗”和文字可读性。 */
    val tint: Color,
    /** 不支持模糊的系统上用的底色（不透明度更高）。 */
    val fallbackTint: Color,
    val rimHighlight: Highlight?,
    val shadow: Shadow?,
)

@Composable
fun rememberGlassStyle(strength: GlassStrength = GlassStrength.Regular): GlassStyle {
    val surface = MaterialTheme.colorScheme.surface
    val dark = surface.luminance() < 0.5f
    return remember(surface, strength) {
        val base = if (dark) Color(0xFF1E1E24) else Color.White
        val alpha = when (strength) {
            GlassStrength.Clear -> if (dark) 0.28f else 0.32f
            GlassStrength.Regular -> if (dark) 0.42f else 0.5f
            GlassStrength.Frosted -> if (dark) 0.62f else 0.7f
        }
        GlassStyle(
            tint = base.copy(alpha = alpha),
            fallbackTint = surface.copy(alpha = 0.94f),
            rimHighlight = Highlight.Default,
            shadow = Shadow(
                radius = 20.dp,
                offset = DpOffset(0.dp, 6.dp),
                color = Color.Black.copy(alpha = if (dark) 0.35f else 0.12f),
            ),
        )
    }
}

enum class GlassStrength { Clear, Regular, Frosted }

/**
 * 胶囊/圆角玻璃：模糊 + 鲜艳度增强 + 边缘折射 + 高光描边 + 投影。
 * [scale] 用来做按压回弹，放进 layerBlock 才能让取样位置跟着缩放。
 */
fun Modifier.liquidGlass(
    backdrop: Backdrop,
    shape: Shape,
    style: GlassStyle,
    blurRadius: Dp = 6.dp,
    refraction: Boolean = true,
    scale: () -> Float = { 1f },
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        vibrancy()
        blur(blurRadius.toPx())
        if (refraction) lens(refractionHeight = 16.dp.toPx(), refractionAmount = 32.dp.toPx(), depthEffect = true)
    },
    highlight = { style.rimHighlight },
    shadow = { style.shadow },
    layerBlock = {
        val s = scale()
        scaleX = s
        scaleY = s
    },
    onDrawSurface = { drawRect(if (isRenderEffectSupported()) style.tint else style.fallbackTint) },
)

/** 顶栏用的磨砂玻璃：整块矩形，模糊更重，不折射、无描边。 */
fun Modifier.frostedBar(backdrop: Backdrop, style: GlassStyle): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { RectangleShape },
    effects = {
        vibrancy()
        blur(28.dp.toPx())
    },
    highlight = null,
    shadow = null,
    onDrawSurface = { drawRect(if (isRenderEffectSupported()) style.tint else style.fallbackTint) },
)

/** 按下时轻微缩小、松手回弹（液态玻璃的“果冻”手感）。 */
@Composable
fun pressScale(interactionSource: MutableInteractionSource, pressed: Float = 0.92f): () -> Float {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressed else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 600f),
        label = "press-scale",
    )
    return { scale }
}

/** 放在 [GlassTopBar] 里的 TopAppBar 用透明底色，玻璃由外层负责。 */
@Composable
fun TransparentBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = Color.Transparent,
    scrolledContainerColor = Color.Transparent,
)

/** 顶栏容器：磨砂玻璃背景 + 底部发丝线。 */
@Composable
fun GlassTopBar(backdrop: Backdrop, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val style = rememberGlassStyle(GlassStrength.Frosted)
    Column(modifier.fillMaxWidth().frostedBar(backdrop, style)) {
        content()
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        )
    }
}

/** 圆形玻璃按钮（新对话、回到最新等）。 */
@Composable
fun GlassCircleButton(
    backdrop: Backdrop,
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    val interaction = remember { MutableInteractionSource() }
    val style = rememberGlassStyle()
    Box(
        modifier
            .size(size)
            .liquidGlass(backdrop, CircleShape, style, scale = pressScale(interaction))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.45f))
    }
}

/** 任意形状的玻璃容器。 */
@Composable
fun GlassSurface(
    backdrop: Backdrop,
    shape: Shape,
    modifier: Modifier = Modifier,
    strength: GlassStrength = GlassStrength.Regular,
    refraction: Boolean = true,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val style = rememberGlassStyle(strength)
    Box(modifier.liquidGlass(backdrop, shape, style, refraction = refraction), contentAlignment = contentAlignment) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() }
    }
}
