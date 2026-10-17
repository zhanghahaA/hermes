package com.hermes.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.mobile.ui.theme.avatarColor

/** 会话头像：取标题第一个字，背景色由标题决定。 */
@Composable
fun SessionAvatar(title: String, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val color = avatarColor(title, dark)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.trim().take(1).ifEmpty { "H" },
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.4f).sp,
        )
    }
}

/** Hermes 自己的头像（聊天里对方一侧）。 */
@Composable
fun HermesAvatar(modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(colors.primary, colors.tertiary))),
        contentAlignment = Alignment.Center,
    ) {
        Text("H", color = colors.onPrimary, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.45f).sp)
    }
}
