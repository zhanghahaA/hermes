package com.hermes.mobile.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.mobile.ui.glass.GlassCircleButton
import com.hermes.mobile.ui.glass.liquidGlass
import com.hermes.mobile.ui.glass.pressScale
import com.hermes.mobile.ui.glass.rememberGlassStyle
import com.kyant.backdrop.Backdrop
import kotlin.math.roundToInt

/** 悬浮栏本身的高度和离屏幕底边的距离；内容区需要据此留出底部空间。 */
val FloatingBarHeight = 64.dp
val FloatingBarMargin = 12.dp

/** 悬浮栏右侧的圆形操作按钮（按当前 Tab 变化，比如“新对话”）。 */
data class FloatingAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/**
 * 悬浮的液态玻璃底栏：左边是 Tab 胶囊，右边是当前页面的主要操作。
 * 选中指示器在切换时会像液体一样被拉长再回弹。
 */
@Composable
fun FloatingTabBar(
    backdrop: Backdrop,
    tabs: List<MainTab>,
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    badges: Map<MainTab, Int>,
    action: FloatingAction?,
    modifier: Modifier = Modifier,
) {
    // 右侧按钮出现/消失时，它占的宽度用弹簧动画变化，Tab 胶囊随之平滑伸缩
    val slot by animateDpAsState(
        targetValue = if (action != null) FloatingBarHeight + ActionGap else 0.dp,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
        label = "action-slot",
    )
    // 退出动画期间 action 已经是 null，继续显示上一次的按钮
    val lastAction = remember { arrayOfNulls<FloatingAction>(1) }
    if (action != null) lastAction[0] = action
    val shown = lastAction[0]

    Row(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = FloatingBarMargin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabPill(backdrop, tabs, selected, onSelect, badges, Modifier.weight(1f))
        Box(Modifier.width(slot).height(FloatingBarHeight), contentAlignment = Alignment.CenterEnd) {
            if (shown != null && slot > 0.dp) {
                val progress = (slot / (FloatingBarHeight + ActionGap)).coerceIn(0f, 1f)
                AnimatedContent(
                    targetState = shown,
                    transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) },
                    contentKey = { it.label },
                    modifier = Modifier
                        .requiredSize(FloatingBarHeight)
                        .graphicsLayer {
                            scaleX = progress
                            scaleY = progress
                            alpha = progress
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        },
                    label = "floating-action",
                ) { current ->
                    GlassCircleButton(
                        backdrop = backdrop,
                        icon = current.icon,
                        contentDescription = current.label,
                        onClick = current.onClick,
                        size = FloatingBarHeight,
                    )
                }
            }
        }
    }
}

private val ActionGap = 10.dp

@Composable
private fun TabPill(
    backdrop: Backdrop,
    tabs: List<MainTab>,
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    badges: Map<MainTab, Int>,
    modifier: Modifier,
) {
    val style = rememberGlassStyle()
    val interaction = remember { MutableInteractionSource() }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val index = tabs.indexOf(selected)
    val indicatorColor = if (dark) Color.White.copy(alpha = 0.14f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val insetPx = with(density) { 5.dp.toPx() }
    var pillWidth by remember { mutableStateOf(0f) }
    var dragIndex by remember { mutableStateOf<Float?>(null) }
    val select by rememberUpdatedState(onSelect)
    val previewIndex = dragIndex?.roundToInt()?.coerceIn(tabs.indices) ?: index

    // 记录移动方向：前沿用硬弹簧冲过去，后沿用软弹簧跟上，形成“拉长再回弹”。
    // 位置以“第几格”为单位动画，胶囊宽度变化时指示器始终对齐格子。
    val previous = remember { intArrayOf(index) }
    val movingRight = remember(index) { (index >= previous[0]).also { previous[0] = index } }
    val fast = spring<Float>(dampingRatio = 0.72f, stiffness = 900f)
    val slow = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)
    val dragging = dragIndex != null
    val target = dragIndex ?: index.toFloat()
    val left by animateFloatAsState(target, if (dragging) snap() else if (movingRight) slow else fast, label = "indicator-left")
    val right by animateFloatAsState(target + 1f, if (dragging) snap() else if (movingRight) fast else slow, label = "indicator-right")
    val lift by animateFloatAsState(if (dragging) 1f else 0f,
        spring(dampingRatio = 0.6f, stiffness = 500f), label = "dock-lift")
    val indicatorStyle = style.copy(tint = indicatorColor,
        fallbackTint = if (dark) Color(0xFF3B445B) else Color(0xFFDDE8FF), shadow = null)

    Box(
        modifier
            .testTag("tab-pill")
            .height(FloatingBarHeight)
            .onSizeChanged { pillWidth = it.width.toFloat() }
            .pointerInput(tabs, rtl) {
                fun position(x: Float): Float {
                    val logical = if (rtl) pillWidth - x else x
                    val cell = ((pillWidth - insetPx * 2) / tabs.size).coerceAtLeast(1f)
                    return ((logical - insetPx) / cell - 0.5f).coerceIn(0f, tabs.lastIndex.toFloat())
                }
                try {
                    detectHorizontalDragGestures(
                        onDragStart = { dragIndex = position(it.x) },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragIndex = position(change.position.x)
                        },
                        onDragEnd = {
                            dragIndex?.let { select(tabs[it.roundToInt().coerceIn(tabs.indices)]) }
                            dragIndex = null
                        },
                        onDragCancel = { dragIndex = null },
                    )
                } finally { dragIndex = null }
            }
            .liquidGlass(backdrop, CircleShape, style, scale = pressScale(interaction, pressed = 0.97f)),
    ) {
        // 指示器自身也从页面取样折射。手指移动时轻轻鼓起，松手用同一套弹簧吸附。
        val cell = with(density) { ((pillWidth - insetPx * 2).coerceAtLeast(0f) / tabs.size).toDp() }
        Box(
            Modifier.offset { IntOffset((insetPx + with(density) { cell.toPx() } * left).roundToInt(), insetPx.roundToInt()) }
                .testTag("dock-indicator")
                .width((cell * (right - left)).coerceAtLeast(1.dp))
                .height(FloatingBarHeight - 10.dp)
                .liquidGlass(backdrop, CircleShape, indicatorStyle, blurRadius = 0.5.dp,
                    scale = { 1f + 0.04f * lift })
                .drawBehind {
                    val rim = if (dark) 0.12f else 0.38f
                    drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = rim + 0.12f * lift), Color.Transparent)),
                        cornerRadius = CornerRadius(size.height / 2))
                },
        )
        Row(Modifier.fillMaxHeight().padding(horizontal = 5.dp)) {
            tabs.forEachIndexed { i, tab ->
                TabItem(
                    tab = tab,
                    selected = i == previewIndex,
                    committed = tab == selected,
                    badge = badges[tab] ?: 0,
                    onClick = { onSelect(tab) },
                    interaction = interaction,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: MainTab,
    selected: Boolean,
    committed: Boolean,
    badge: Int,
    onClick: () -> Unit,
    interaction: MutableInteractionSource,
    modifier: Modifier,
) {
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        label = "tab-color",
    )
    Column(
        modifier
            .fillMaxHeight()
            .semantics { this.selected = committed }
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) {
            Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        }
        Text(tab.label, color = color, fontSize = 11.sp, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
    }
}
