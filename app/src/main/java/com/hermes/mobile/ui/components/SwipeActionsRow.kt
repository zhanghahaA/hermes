package com.hermes.mobile.ui.components

import androidx.compose.animation.core.Animatable
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
 */
@Composable
fun SwipeActionsRow(
    isOpen: Boolean,
    onOpenChange: (Boolean) -> Unit,
    actions: List<SwipeAction>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val revealPx = with(LocalDensity.current) { ActionWidth.toPx() } * actions.size
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val settleSpec = remember { spring<Float>(dampingRatio = 0.82f, stiffness = 520f) }

    LaunchedEffect(isOpen, revealPx) {
        offset.animateTo(if (isOpen) -revealPx else 0f, settleSpec)
    }

    // 收起时不创建按钮：既省绘制，也避免读屏软件读到看不见的按钮
    val revealed by remember { derivedStateOf { offset.value < -0.5f } }

    Box(modifier.clipToBounds()) {
        // 底层按钮，按露出的比例从右往左展开
        if (revealed) Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
            actions.forEach { action ->
                Column(
                    Modifier
                        .width(ActionWidth)
                        .fillMaxHeight()
                        .graphicsLayer { alpha = (-offset.value / revealPx).coerceIn(0f, 1f) }
                        .background(action.container)
                        .clickable {
                            onOpenChange(false)
                            action.onClick()
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(action.icon, contentDescription = null, tint = action.content, modifier = Modifier.size(22.dp))
                    Text(action.label, color = action.content, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Box(
            Modifier
                .graphicsLayer { translationX = offset.value }
                .pointerInput(revealPx) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        val startedOpen = offset.value < -1f
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
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (abs(dy) > slop && abs(dy) > abs(dx)) return@awaitEachGesture
                            if (!startedOpen && dx > slop) return@awaitEachGesture
                            if (abs(dx) > slop && abs(dx) > abs(dy)) {
                                change.consume()
                                break
                            }
                        }
                        var value = offset.value + dx
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            tracker.addPosition(change.uptimeMillis, change.position)
                            change.consume()
                            if (!change.pressed) break
                            value += change.positionChangeIgnoreConsumed().x
                            // 超出范围时加阻尼
                            val shown = when {
                                value > 0f -> value * 0.25f
                                value < -revealPx -> -revealPx + (value + revealPx) * 0.3f
                                else -> value
                            }
                            scope.launch { offset.snapTo(shown) }
                        }
                        val velocity = tracker.calculateVelocity().x
                        val open = velocity < -600f || (velocity < 600f && offset.value < -revealPx / 2f)
                        onOpenChange(open)
                        // isOpen 没变时 LaunchedEffect 不会重跑，这里自己回到终点
                        scope.launch { offset.animateTo(if (open) -revealPx else 0f, settleSpec, initialVelocity = velocity) }
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
