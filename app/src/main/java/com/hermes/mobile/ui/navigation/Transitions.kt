package com.hermes.mobile.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/*
 * 页面切换动画。
 *
 * 返回（包括系统的预测性返回手势）时，Navigation 会按手指拖动的进度“拖着”下面的
 * popExit / popEnter 动画走，所以动画本身就是跟手的：
 *   - 当前页：缩小、圆角、跟着手指往右移，松手后滑出；取消则弹回
 *   - 上一页：从左侧轻微视差滑回，同时由暗变亮
 * 需要在 Manifest 里开启 android:enableOnBackInvokedCallback="true"（已开启）。
 */

private const val DURATION = 400

/** Material 3 的强调缓动。 */
private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** 手势早期位移小、后期加速，拖动时先“缩成卡片”，松手才真正滑走。 */
private val LateAccelerate = CubicBezierEasing(0.55f, 0f, 1f, 1f)

val forwardEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(tween(DURATION, easing = Emphasized)) { it }
}

val forwardExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(tween(DURATION, easing = Emphasized)) { -it / 4 } +
        fadeOut(tween(DURATION), targetAlpha = 0.6f)
}

val backEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(tween(DURATION, easing = Emphasized)) { -it / 4 } +
        scaleIn(tween(DURATION, easing = Emphasized), initialScale = 0.96f) +
        fadeIn(tween(DURATION), initialAlpha = 0.6f)
}

val backExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    scaleOut(tween(DURATION, easing = Emphasized), targetScale = 0.86f) +
        slideOutHorizontally(tween(DURATION, easing = LateAccelerate)) { it }
}

/**
 * 每个页面外面包一层：动画进行中（包括手势拖动中）出现圆角和阴影，
 * 静止时恢复全屏直角。圆角大小也是跟着手势进度走的。
 */
@Composable
fun AnimatedContentScope.PredictiveBackCard(content: @Composable () -> Unit) {
    val corner by transition.animateDp(label = "page-corner") { state ->
        if (state == EnterExitState.Visible) 0.dp else 32.dp
    }
    val elevation by transition.animateFloat(label = "page-elevation") { state ->
        if (state == EnterExitState.Visible) 0f else 1f
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                shape = RoundedCornerShape(corner)
                clip = corner > 0.dp
                shadowElevation = elevation * 24.dp.toPx()
                ambientShadowColor = Color.Black
                spotShadowColor = Color.Black
            }
            .background(MaterialTheme.colorScheme.background),
    ) {
        content()
    }
}

/** 等同于 composable<T>，但自动套上 [PredictiveBackCard]。 */
inline fun <reified T : Any> NavGraphBuilder.screen(
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable<T> { entry -> PredictiveBackCard { content(entry) } }
}
