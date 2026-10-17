package com.hermes.mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.model.ConnectionState
import com.hermes.mobile.ui.theme.LocalHermesColors

@Composable
fun connectionColor(state: ConnectionState): Color {
    val target = when (state) {
        is ConnectionState.Connected -> LocalHermesColors.current.online
        ConnectionState.Connecting -> LocalHermesColors.current.warning
        is ConnectionState.Failed -> MaterialTheme.colorScheme.error
        ConnectionState.Disconnected -> MaterialTheme.colorScheme.outline
    }
    return animateColorAsState(target, label = "connection-color").value
}

fun connectionLabel(state: ConnectionState): String = when (state) {
    is ConnectionState.Connected -> "${state.latencyMs} ms"
    ConnectionState.Connecting -> "连接中…"
    is ConnectionState.Failed -> "连接失败"
    ConnectionState.Disconnected -> "未连接"
}

@Composable
fun StatusDot(state: ConnectionState, modifier: Modifier = Modifier, size: Int = 8) {
    val color = connectionColor(state)
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (state == ConnectionState.Connecting) 0.3f else 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse-alpha",
    )
    Box(
        modifier
            .size(size.dp)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}

/** 顶栏里的连接状态胶囊：● 家里 NAS · 23 ms ⌄，点击切换连接。 */
@Composable
fun ConnectionPill(
    name: String,
    state: ConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusDot(state)
            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(
                connectionLabel(state),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Icon(Icons.Rounded.ExpandMore, contentDescription = "切换连接", modifier = Modifier.size(18.dp))
        }
    }
}
