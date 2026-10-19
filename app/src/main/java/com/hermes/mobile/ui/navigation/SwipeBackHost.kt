package com.hermes.mobile.ui.navigation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import com.hermes.mobile.platform.SystemBackHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 页面容器，支持跟手返回：
 * - 在页面任意位置向右滑（不只是屏幕边缘），页面跟着手指走，上一页从左侧露出来；
 * - 动画进行中手指按下会立刻“接住”页面，可以继续拖或者推回去；
 * - 系统返回手势（支持预测性返回的机型）同样实时驱动；不支持的机型松手后播放同一套动画。
 */
@Composable
fun SwipeBackHost(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    content: @Composable (route: Any) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val stateHolder = rememberSaveableStateHolder()
    DisposableEffect(navigator, scope) {
        navigator.scope = scope
        navigator.onRemoved = { stateHolder.removeState(it.key) }
        onDispose { navigator.scope = null }
    }

    SystemBackHandler(enabled = navigator.canGoBack) { gesture ->
        val top = navigator.entries.last()
        try {
            // 系统手势：页面按进度缩小、往手势那一侧偏一点（和底部面板一样）；松手后照常滑出
            gesture.collect { g ->
                top.backFromRight = g.fromRightEdge
                top.back.snapTo(g.progress)
            }
            navigator.settle(top, commit = true, velocity = 0f)
        } catch (e: CancellationException) {
            scope.launch { navigator.settle(top, commit = false, velocity = 0f) }
            throw e
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .swipeBackGesture(navigator, scope),
    ) {
        val entries = navigator.entries
        val top = entries.last()
        // 只组合最上面两层：顶层和拖动时露出来的那一层。更深的页面靠 SaveableStateHolder 保存状态。
        entries.takeLast(2).forEach { entry ->
            key(entry.key) {
                val pageModifier = when {
                    entries.size == 1 -> Modifier
                    entry === top -> Modifier.topPage(
                        fraction = { entry.offset.value },
                        back = { entry.back.value },
                        backFromRight = { entry.backFromRight },
                    )
                    else -> Modifier.underPage { maxOf(top.offset.value, top.back.value * 0.02f) }
                }
                Box(pageModifier.fillMaxSize().blockTouchesBehind()) {
                    CompositionLocalProvider(LocalNavEntry provides entry) {
                        stateHolder.SaveableStateProvider(entry.key) { content(entry.route) }
                    }
                }
            }
        }
    }
}

private val LocalNavEntry = staticCompositionLocalOf<Navigator.Entry?> { null }

/**
 * 这个页面在屏幕上时，关掉“页面任意位置右滑返回”（手指要拖页面里的内容，比如远程桌面）。
 * 左上角的返回按钮和系统的返回手势照常可用。
 */
@Composable
fun BlockSwipeBack() {
    val entry = LocalNavEntry.current ?: return
    DisposableEffect(entry) {
        entry.swipeBackBlocks++
        onDispose { entry.swipeBackBlocks-- }
    }
}

/**
 * 顶层页面。
 * - [fraction]：滑出的比例（应用内右滑、返回动画），页面右移，同时缩小、出现圆角和阴影；
 * - [back]：系统预测性返回的进度，页面原地按比例缩到 90%，并往手势那一侧偏一点（Material 的预测性返回样式）。
 */
internal fun Modifier.topPage(
    fraction: () -> Float,
    back: () -> Float = { 0f },
    backFromRight: () -> Boolean = { false },
): Modifier = graphicsLayer {
    val f = fraction()
    val b = back()
    // Material 规范：最多偏移 屏宽/20 - 8dp，左边缘滑往右偏，右边缘滑往左偏
    val shift = (size.width / 20f - 8.dp.toPx()).coerceAtLeast(0f) * b * (if (backFromRight()) -1f else 1f)
    translationX = f * size.width + shift
    val s = minOf(1f - 0.08f * (f * 3f).coerceAtMost(1f), 1f - 0.1f * b)
    scaleX = s
    scaleY = s
    val corner = maxOf((f * 4f).coerceAtMost(1f), (b * 3f).coerceAtMost(1f))
    if (corner > 0f) {
        shape = RoundedCornerShape(28.dp.toPx() * corner)
        clip = true
        shadowElevation = 18.dp.toPx()
    } else {
        clip = false
        shadowElevation = 0f
    }
}

/**
 * 下面一层：原地不动，由暗变亮。静止时完全不绘制。
 * 不做左右视差：上层页面拖动时会缩小，下层要是往左偏，右边就会露出一条没有内容的白边。
 */
internal fun Modifier.underPage(topFraction: () -> Float): Modifier = graphicsLayer {
    val f = topFraction()
    alpha = if (f <= 0f) 0f else 1f
}.drawWithContent {
    drawContent()
    drawRect(Color.Black.copy(alpha = 0.22f * (1f - topFraction())))
}

/** 让被盖住的页面收不到触摸（Compose 里上层没有触摸处理的区域会把事件漏给下层）。 */
private fun Modifier.blockTouchesBehind(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent() }
}

private fun Modifier.swipeBackGesture(navigator: Navigator, scope: CoroutineScope): Modifier = pointerInput(navigator) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!navigator.canGoBack) return@awaitEachGesture
        val top = navigator.entries.last()
        if (top.swipeBackBlocks > 0) return@awaitEachGesture
        val width = size.width.toFloat()
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var fraction = top.offset.value

        if (top.offset.isRunning && fraction > 0.02f) {
            // 动画中被按住：立刻停在当前位置，直接进入拖动
            down.consume()
            scope.launch { top.offset.snapTo(fraction) }
        } else {
            // 先判断方向：明显向右、且子组件（横向滚动的代码块等）没有抢走，才开始返回手势
            var dx = 0f
            var dy = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed) return@awaitEachGesture
                val delta = change.positionChangeIgnoreConsumed()
                if (delta == Offset.Zero) continue
                if (change.isConsumed) return@awaitEachGesture
                dx += delta.x
                dy += delta.y
                tracker.addPosition(change.uptimeMillis, change.position)
                if (abs(dy) > slop && abs(dy) > abs(dx)) return@awaitEachGesture
                if (dx < -slop) return@awaitEachGesture
                if (dx > slop && dx > abs(dy) * 1.2f) {
                    change.consume()
                    // 判断方向时手指已经走过的这段也算上，页面直接对齐到手指，不会落后一截
                    fraction = (fraction + dx / width).coerceIn(0f, 1f)
                    val start = fraction
                    scope.launch { top.offset.snapTo(start) }
                    break
                }
            }
        }

        // 拖动中：在 Initial 阶段吃掉事件，下面的列表、按钮都不会再响应
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            tracker.addPosition(change.uptimeMillis, change.position)
            change.consume()
            if (!change.pressed) break
            fraction = (fraction + change.positionChangeIgnoreConsumed().x / width).coerceIn(0f, 1f)
            val target = fraction
            scope.launch { top.offset.snapTo(target) }
        }

        val velocity = tracker.calculateVelocity().x / width
        val commit = velocity > 1.1f || (fraction > 0.4f && velocity > -0.4f)
        scope.launch { navigator.settle(top, commit, velocity) }
    }
}
