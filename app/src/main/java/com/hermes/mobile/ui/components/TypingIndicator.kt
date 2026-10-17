package com.hermes.mobile.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.hermes.mobile.ui.theme.LocalHermesColors

/** “对方正在输入”的三个跳动圆点，放在 Hermes 一侧的气泡里。 */
@Composable
fun TypingBubble(modifier: Modifier = Modifier) {
    val bubble = LocalHermesColors.current.assistantBubble
    Row(
        modifier = modifier
            .background(bubble, MaterialTheme.shapes.large)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val transition = rememberInfiniteTransition(label = "typing")
        repeat(3) { index ->
            val progress by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 160),
                ),
                label = "dot-$index",
            )
            Box(
                Modifier
                    .size(7.dp)
                    .graphicsLayer {
                        translationY = -4.dp.toPx() * progress
                        alpha = 0.35f + 0.65f * progress
                    }
                    .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
            )
        }
    }
}
