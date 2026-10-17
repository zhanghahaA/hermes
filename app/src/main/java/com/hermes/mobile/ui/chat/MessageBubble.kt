package com.hermes.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.DeliveryStatus
import com.hermes.mobile.data.model.ImageRef
import com.hermes.mobile.data.model.Role
import com.hermes.mobile.ui.components.HermesAvatar
import com.hermes.mobile.ui.components.MarkdownText
import com.hermes.mobile.ui.components.TypingBubble
import com.hermes.mobile.ui.components.clockTime
import com.hermes.mobile.ui.theme.LocalHermesColors

private val Big = 20.dp
private val Small = 6.dp
private const val AVATAR_GAP = 36

/** 同一个人连续发的消息合成一组：组内相邻的那一侧圆角变小，组的最后一条显示时间和头像。 */
private fun bubbleShape(role: Role, first: Boolean): RoundedCornerShape = when (role) {
    Role.User -> RoundedCornerShape(topStart = Big, topEnd = if (first) Big else Small, bottomEnd = Small, bottomStart = Big)
    Role.Assistant -> RoundedCornerShape(topStart = if (first) Big else Small, topEnd = Big, bottomEnd = Big, bottomStart = Small)
}

@Composable
fun MessageBubble(
    message: ChatItem.Message,
    first: Boolean,
    last: Boolean,
    maxWidth: Dp,
    onLongPress: () -> Unit,
    onRetry: () -> Unit,
    onCopyCode: (String) -> Unit,
) {
    val colors = LocalHermesColors.current
    val haptics = LocalHapticFeedback.current
    val isUser = message.role == Role.User
    val container = if (isUser) colors.userBubble else colors.assistantBubble
    val content = if (isUser) colors.onUserBubble else colors.onAssistantBubble

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = if (first) 6.dp else 2.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!isUser) {
            if (last) HermesAvatar() else Spacer(Modifier.width(28.dp))
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            val shape = bubbleShape(message.role, first)
            Surface(
                color = container,
                contentColor = content,
                shape = shape,
                modifier = Modifier
                    .widthIn(max = if (isUser) maxWidth * 0.8f else maxWidth - AVATAR_GAP.dp - 24.dp)
                    .clip(shape)
                    .combinedClickable(
                        onClick = { if (message.status == DeliveryStatus.Failed) onRetry() },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress()
                        },
                    ),
            ) {
                Column {
                    if (message.images.isNotEmpty()) {
                        ImageGrid(message.images, Modifier.padding(4.dp))
                    }
                    if (message.text.isNotBlank()) {
                        Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            if (isUser) {
                                Text(message.text, style = MaterialTheme.typography.bodyLarge)
                            } else {
                                MarkdownText(message.text, onCopyCode = onCopyCode)
                            }
                        }
                    }
                }
            }
            if (last || message.status != DeliveryStatus.Sent) {
                MessageMeta(message, onRetry)
            }
        }
    }
}

@Composable
private fun MessageMeta(message: ChatItem.Message, onRetry: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (message.status) {
            DeliveryStatus.Failed -> {
                Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                Text(
                    "发送失败，点击重试",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable(onClick = onRetry),
                )
            }
            DeliveryStatus.Sending -> {
                Text(clockTime(message.time), style = MaterialTheme.typography.labelSmall, color = muted)
                Icon(Icons.Rounded.AccessTime, contentDescription = "发送中", tint = muted, modifier = Modifier.size(12.dp))
            }
            DeliveryStatus.Sent -> {
                Text(clockTime(message.time), style = MaterialTheme.typography.labelSmall, color = muted)
                if (message.role == Role.User) {
                    Icon(Icons.Rounded.Done, contentDescription = "已送达", tint = muted, modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}

/** 图片占位：渐变色块。接入真实图片后换成 Coil 的 AsyncImage。 */
@Composable
fun ImageGrid(images: List<ImageRef>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        images.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { image ->
                    ImagePlaceholder(
                        image,
                        Modifier
                            .width(if (images.size == 1) 240.dp else 118.dp)
                            .aspectRatio(if (images.size == 1) image.aspect else 1f),
                    )
                }
            }
        }
    }
}

@Composable
fun ImagePlaceholder(image: ImageRef, modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(16.dp)) {
    Box(
        modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.hsv(image.hue % 360f, 0.35f, 0.92f),
                        Color.hsv((image.hue + 50f) % 360f, 0.55f, 0.70f),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Image, contentDescription = "图片", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(32.dp))
    }
}

/** Hermes 正在处理：对方一侧的“正在输入”气泡。 */
@Composable
fun TypingRow() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        HermesAvatar()
        Spacer(Modifier.width(8.dp))
        TypingBubble()
    }
}

/** 打开“逐字显示回复”时，正在生成的那条回复。 */
@Composable
fun StreamingRow(text: String, maxWidth: Dp) {
    MessageBubble(
        message = ChatItem.Message("streaming", 0, Role.Assistant, text),
        first = true,
        last = true,
        maxWidth = maxWidth,
        onLongPress = {},
        onRetry = {},
        onCopyCode = {},
    )
}

@Composable
fun DateSeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(50)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}
