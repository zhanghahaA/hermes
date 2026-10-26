package com.hermes.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.style.TextOverflow
import com.hermes.mobile.data.model.Quote
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

/** 某条消息里当前选中的文字。 */
class MessageSelection(val message: ChatItem.Message, val text: String, private val state: SelectionState) {
    fun clear() = state.clear()
    fun selectAll() = state.selectAll()
}

@Composable
fun MessageBubble(
    message: ChatItem.Message,
    first: Boolean,
    last: Boolean,
    maxWidth: Dp,
    onRetry: () -> Unit,
    onCopyCode: (String) -> Unit,
    highlighted: Boolean = false,
    onSelectionChange: (messageId: String, selection: MessageSelection?) -> Unit = { _, _ -> },
    onQuote: ((Quote) -> Unit)? = null,
    onQuoteClick: (messageId: String) -> Unit = {},
    onMore: () -> Unit = {},
    showMeta: Boolean = true,
    workStyle: Boolean = false,
) {
    val colors = LocalHermesColors.current
    val isUser = message.role == Role.User
    val plain = workStyle && !isUser
    val container = if (isUser) colors.userBubble else if (plain) Color.Transparent else colors.assistantBubble
    val content = if (isUser) colors.onUserBubble else colors.onAssistantBubble

    // 长按文字进入选中；选中的内容实时上报给聊天页，由它显示“追问/复制”操作栏
    val selectionState = rememberSelectionState()
    val reportSelection by rememberUpdatedState(onSelectionChange)
    LaunchedEffect(selectionState, message.id) {
        snapshotFlow { selectionState.selectedTexts.joinToString("") { it.text } }
            .collect { text ->
                reportSelection(message.id, if (text.isBlank()) null else MessageSelection(message, text, selectionState))
            }
    }

    val highlight by animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent,
        animationSpec = tween(if (highlighted) 150 else 900),
        label = "quote-highlight",
    )

    // 只有 Hermes 已完成的回复能右往左滑引用
    val quoteHandler = onQuote?.takeIf { message.quotable }
    SwipeToQuote(enabled = quoteHandler != null, onQuote = { Quote.of(message)?.let { quoteHandler?.invoke(it) } }) {
        // 头像和气泡底边对齐，时间放在气泡下方（Hermes 一侧缩进到头像右边）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind { drawRect(highlight) }
                .padding(start = 12.dp, end = 12.dp, top = if (first) 6.dp else 2.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                if (!isUser && !plain) {
                    if (last) HermesAvatar() else Spacer(Modifier.width(28.dp))
                    Spacer(Modifier.width(8.dp))
                }
                val shape = bubbleShape(message.role, first)
                Surface(
                    color = container,
                    contentColor = content,
                    shape = shape,
                    modifier = Modifier
                        .widthIn(max = if (isUser) maxWidth * 0.8f else if (plain) maxWidth - 24.dp else maxWidth - AVATAR_GAP.dp - 24.dp)
                        .clip(shape)
                        .then(
                            if (message.status == DeliveryStatus.Failed) Modifier.clickable(onClick = onRetry) else Modifier,
                        ),
                ) {
                    Column {
                        message.quote?.let { quote ->
                            QuoteBlock(
                                quote = quote,
                                tint = content,
                                onClick = { onQuoteClick(quote.messageId) },
                                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp),
                            )
                        }
                        if (message.images.isNotEmpty()) {
                            ImageGrid(
                                message.images,
                                Modifier
                                    .padding(4.dp)
                                    .combinedClickable(onClick = {}, onLongClick = onMore),
                            )
                        }
                        if (message.files.isNotEmpty()) {
                            FileChips(message.files, content, Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp))
                        }
                        if (message.text.isNotBlank()) {
                            SelectionContainer(state = selectionState, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                if (isUser) {
                                    Text(message.text, style = MaterialTheme.typography.bodyLarge)
                                } else {
                                    MarkdownText(message.text, onCopyCode = onCopyCode)
                                }
                            }
                        }
                    }
                }
            }
            if (showMeta && (last || message.status != DeliveryStatus.Sent)) {
                MessageMeta(message, onRetry, Modifier.padding(start = if (isUser || plain) 0.dp else AVATAR_GAP.dp))
            }
        }
    }
}

/** 用户消息里显示的“引用了哪段回复”。点一下跳回原消息。 */
@Composable
fun QuoteBlock(quote: Quote, tint: Color, onClick: (() -> Unit)?, modifier: Modifier = Modifier, maxLines: Int = 2) {
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.12f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .height(IntrinsicSize.Min)
            .padding(end = 10.dp),
    ) {
        Box(
            Modifier
                .padding(vertical = 6.dp)
                .width(3.dp)
                .fillMaxHeight()
                .background(tint.copy(alpha = 0.6f), RoundedCornerShape(2.dp)),
        )
        Column(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp)) {
            Text(
                if (quote.partial) "Hermes · 片段" else "Hermes",
                style = MaterialTheme.typography.labelMedium,
                color = tint.copy(alpha = 0.85f),
            )
            Text(
                quote.text,
                style = MaterialTheme.typography.bodyMedium,
                color = tint.copy(alpha = 0.8f),
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MessageMeta(message: ChatItem.Message, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.padding(horizontal = 6.dp, vertical = 3.dp),
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

/** Hermes 正在处理：对方一侧的“正在输入”气泡。 */
@Composable
fun TypingRow() {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("chat-typing")
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
fun StreamingRow(text: String, maxWidth: Dp, workStyle: Boolean = false) {
    // 生成中的回复不能引用也不能选中追问（status 是 Sending，quotable 为 false）
    MessageBubble(
        message = ChatItem.Message("streaming", 0, Role.Assistant, text, status = DeliveryStatus.Sending),
        first = true,
        last = true,
        maxWidth = maxWidth,
        onRetry = {},
        onCopyCode = {},
        showMeta = false,
        workStyle = workStyle,
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
