package com.hermes.mobile.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 页面栈。每个页面有一个 [Entry.offset]：0 = 完全显示，1 = 完全滑出到右侧。
 *
 * 进入、返回、手指拖动全部只是在改这一个值，所以动画随时可以被手指“接住”（实时打断），
 * 松手后再根据位置和速度决定是完成返回还是弹回去。
 *
 * 放在 ViewModel 里，转屏不会丢页面栈。
 */
class Navigator(root: Any) {

    class Entry internal constructor(val route: Any, internal val key: Int, initialOffset: Float) {
        internal val offset = Animatable(initialOffset)

        /** 正在播放返回动画，避免重复返回。 */
        internal var leaving by mutableStateOf(false)
    }

    private var nextKey = 0
    internal val entries = mutableStateListOf(Entry(root, nextKey++, 0f))

    internal var scope: CoroutineScope? = null
    internal var onRemoved: (Entry) -> Unit = {}

    val canGoBack: Boolean get() = entries.size > 1

    /** 最上面的页面。 */
    val current: Any get() = entries.last().route

    /** 打开新页面。[clearAboveRoot] 为 true 时，新页面滑入后把它和首页之间的页面都移除。 */
    fun push(route: Any, clearAboveRoot: Boolean = false) {
        val scope = scope
        val entry = Entry(route, nextKey++, if (scope != null) 1f else 0f)
        val stale = if (clearAboveRoot) entries.drop(1) else emptyList()
        entries += entry
        if (scope == null) {
            stale.forEach(::remove)
            return
        }
        scope.launch {
            try {
                entry.offset.animateTo(0f, EnterSpec)
            } finally {
                stale.forEach(::remove)
            }
        }
    }

    /** 返回上一页（带动画）。 */
    fun pop() {
        val top = entries.lastOrNull() ?: return
        if (!canGoBack || top.leaving) return
        scope?.launch { settle(top, commit = true, velocity = 0f) } ?: remove(top)
    }

    /**
     * 让页面停到终点：[commit] 为 true 时滑出并移出栈，否则弹回原位。
     * [velocity] 是松手时的速度（单位：每秒移动多少个屏幕宽度），让动画接着手指的速度走。
     */
    internal suspend fun settle(entry: Entry, commit: Boolean, velocity: Float) {
        if (commit) {
            entry.leaving = true
            try {
                entry.offset.animateTo(1f, ExitSpec, initialVelocity = velocity.coerceAtLeast(0f))
                remove(entry)
            } catch (e: CancellationException) {
                // 被手指接住了，留在栈里
                entry.leaving = false
                throw e
            }
        } else {
            entry.leaving = false
            entry.offset.animateTo(0f, CancelSpec, initialVelocity = velocity)
        }
    }

    private fun remove(entry: Entry) {
        if (entries.remove(entry)) onRemoved(entry)
    }

    private companion object {
        val EnterSpec = spring<Float>(dampingRatio = 1f, stiffness = 420f)
        val ExitSpec = spring<Float>(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)
        val CancelSpec = spring<Float>(dampingRatio = 0.86f, stiffness = 520f)
    }
}
