package com.hermes.mobile.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp

private val TriggerDistance = 64.dp
private val MaxDistance = 96.dp

/**
 * 从右往左滑消息来引用（Telegram 式）：消息跟手左移，右侧出现回复图标，
 * 超过触发距离时轻震一下，松手弹回并触发 [onQuote]。
 * 只认向左的横滑：向右滑交给返回手势，竖滑交给列表。
 */
@Composable
fun SwipeToQuote(enabled: Boolean, onQuote: () -> Unit, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val density = LocalDensity.current
    val trigger = with(density) { TriggerDistance.toPx() }
    val max = with(density) { MaxDistance.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentOnQuote by rememberUpdatedState(onQuote)

    Box(
        Modifier.pointerInput(trigger) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var dx = 0f
                var dy = 0f
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (!change.pressed) return@awaitEachGesture
                    val delta = change.positionChangeIgnoreConsumed()
                    if (delta == Offset.Zero) continue
                    if (change.isConsumed) return@awaitEachGesture
                    dx += delta.x
                    dy += delta.y
                    if (abs(dy) > slop && abs(dy) > abs(dx)) return@awaitEachGesture
                    if (dx > slop) return@awaitEachGesture
                    if (dx < -slop && abs(dx) > abs(dy) * 1.2f) {
                        change.consume()
                        break
                    }
                }
                var raw = dx
                var armed = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    raw = (raw + change.positionChangeIgnoreConsumed().x).coerceAtMost(0f)
                    // 越往左阻力越大，最多拉到 MaxDistance
                    val value = -max * (1f - exp(-1.6f * -raw / max))
                    if (!armed && -value >= trigger) {
                        armed = true
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } else if (armed && -value < trigger) {
                        armed = false
                    }
                    scope.launch { offset.snapTo(value) }
                }
                if (armed) currentOnQuote()
                scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 600f)) }
            }
        },
    ) {
        Box(Modifier.graphicsLayer { translationX = offset.value }) { content() }
        val progress = { (-offset.value / trigger).coerceIn(0f, 1f) }
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .graphicsLayer {
                    val p = progress()
                    alpha = p
                    scaleX = 0.5f + 0.5f * p
                    scaleY = 0.5f + 0.5f * p
                }
                .size(32.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}
