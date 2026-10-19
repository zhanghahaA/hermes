package com.hermes.mobile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.splineBasedDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

data class SwipeAction(
    val label: String,
    val icon: ImageVector,
    val container: Color,
    val content: Color,
    val onClick: () -> Unit,
)

private val ActionWidth = 76.dp

/**
 * 左滑露出操作按钮（类似微信/Telegram）。按钮点了才执行，执行后自动收回；
 * 同一时间只展开一行由调用方通过 [isOpen] / [onOpenChange] 控制。
 *
 * 手感：拖动时内容严格跟着手指（不经过协程，没有延迟）；松手后按速度推算惯性能滑到哪里，
 * 再决定展开还是收回，并带着松手时的速度弹过去；动画途中手指按下能直接接住继续拖。
 */
@Composable
fun SwipeActionsRow(
    isOpen: Boolean,
    onOpenChange: (Boolean) -> Unit,
    actions: List<SwipeAction>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val revealPx = with(density) { ActionWidth.toPx() } * actions.size
    val anim = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val decay = remember(density) { splineBasedDecay<Float>(density) }
    val settleSpec = remember { spring<Float>(dampingRatio = 0.78f, stiffness = 360f) }
    val offset: () -> Float = { if (dragging) dragOffset else anim.value }

    LaunchedEffect(isOpen, revealPx) {
        if (!dragging) anim.animateTo(if (isOpen) -revealPx else 0f, settleSpec, anim.velocity)
    }

    /** 超出范围时越拉越难拉（橡皮筋）。 */
    fun rubber(raw: Float): Float = when {
        raw > 0f -> raw * 0.25f
        raw < -revealPx -> -revealPx + (raw + revealPx) * 0.35f
        else -> raw
    }

    // 收起时不创建按钮：既省绘制，也避免读屏软件读到看不见的按钮
    val revealed by remember { derivedStateOf { offset() < -0.5f } }

    Box(modifier.clipToBounds()) {
        // 底层按钮：跟着露出的宽度一起伸展，拉过头时也跟着变宽
        if (revealed) Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
            actions.forEach { action ->
                Column(
                    Modifier
                        .fillMaxHeight()
                        .layout { measurable, constraints ->
                            val w = (-offset() / actions.size).coerceAtLeast(0f).toInt()
                            val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
                            layout(w, placeable.height) { placeable.place(0, 0) }
                        }
                        .background(action.container)
                        .clickable {
                            onOpenChange(false)
                            action.onClick()
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Column(
                        Modifier.graphicsLayer {
                            // 露出一半以上才清楚显示，按钮出现得更自然
                            alpha = ((-offset() / revealPx - 0.25f) / 0.6f).coerceIn(0f, 1f)
                            val s = 0.8f + 0.2f * alpha
                            scaleX = s
                            scaleY = s
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(action.icon, contentDescription = null, tint = action.content, modifier = Modifier.size(22.dp))
                        Text(action.label, color = action.content, style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        Box(
            Modifier
                .graphicsLayer { translationX = offset() }
                .pointerInput(revealPx) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 这一行自己跟着手指在动，事件里的坐标不能直接拿来算速度；
                        // 用累加的手指位移（相对屏幕不动的坐标）来算
                        val tracker = VelocityTracker()
                        var finger = 0f
                        tracker.addPosition(down.uptimeMillis, Offset.Zero)
                        val startValue = anim.value
                        var raw: Float
                        if (anim.isRunning && abs(startValue) > 1f) {
                            // 动画途中被按住：立刻接住，直接进入拖动
                            down.consume()
                            scope.launch { anim.stop() }
                            dragOffset = startValue
                            dragging = true
                            raw = startValue
                        } else {
                            val startedOpen = startValue < -1f
                            var dx = 0f
                            var dy = 0f
                            // 判断方向：关着时只认向左；开着时左右都认
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                                if (!change.pressed) return@awaitEachGesture
                                val delta = change.positionChangeIgnoreConsumed()
                                if (delta == Offset.Zero) continue
                                if (change.isConsumed) return@awaitEachGesture
                                dx += delta.x
                                dy += delta.y
                                finger += delta.x
                                tracker.addPosition(change.uptimeMillis, Offset(finger, 0f))
                                if (abs(dy) > slop && abs(dy) > abs(dx)) return@awaitEachGesture
                                if (!startedOpen && dx > slop) return@awaitEachGesture
                                if (abs(dx) > slop && abs(dx) > abs(dy)) {
                                    change.consume()
                                    break
                                }
                            }
                            // 判断方向时走过的距离也算上，内容直接对齐到手指
                            raw = startValue + dx
                            dragOffset = rubber(raw)
                            dragging = true
                        }
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val dx = change.positionChangeIgnoreConsumed().x
                            finger += dx
                            tracker.addPosition(change.uptimeMillis, Offset(finger, 0f))
                            change.consume()
                            if (!change.pressed) break
                            raw += dx
                            dragOffset = rubber(raw)
                        }
                        val velocity = tracker.calculateVelocity().x
                        // 惯性：按松手速度推算会滑到哪里，过了一半就展开
                        val projected = decay.calculateTargetValue(dragOffset, velocity)
                        val open = projected < -revealPx / 2f
                        val from = dragOffset
                        scope.launch {
                            anim.snapTo(from)
                            dragging = false
                            anim.animateTo(if (open) -revealPx else 0f, settleSpec, initialVelocity = velocity)
                        }
                        onOpenChange(open)
                    }
                },
        ) {
            content()
            if (isOpen) {
                // 展开时点内容区只收回，不触发点击
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(indication = null, interactionSource = null) { onOpenChange(false) },
                )
            }
        }
    }
}
