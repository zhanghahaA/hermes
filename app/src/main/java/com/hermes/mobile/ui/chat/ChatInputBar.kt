package com.hermes.mobile.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.hermes.mobile.data.model.Quote
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.model.ImageRef
import com.hermes.mobile.ui.glass.GlassCircleButton
import com.hermes.mobile.ui.glass.GlassSurface
import com.kyant.backdrop.Backdrop

enum class SendButtonMode { Send, Stop }

private val InputShape = RoundedCornerShape(26.dp)

/**
 * 悬浮的液态玻璃输入栏：左边圆形“+”按钮，右边是输入胶囊（发送/停止按钮嵌在胶囊里）。
 * 跟随键盘上移，消息列表在它下面滚动。
 */
@Composable
fun ChatInputBar(
    backdrop: Backdrop,
    text: String,
    onTextChange: (String) -> Unit,
    attachments: List<ImageRef>,
    onRemoveAttachment: (ImageRef) -> Unit,
    onAttach: () -> Unit,
    mode: SendButtonMode,
    onSend: () -> Unit,
    onStop: () -> Unit,
    enterToSend: Boolean,
    quote: Quote? = null,
    onClearQuote: () -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() },
    placeholder: String = if (quote != null) "针对这段追问…" else "给 Hermes 发消息",
) {
    val canSend = text.isNotBlank() || attachments.isNotEmpty()
    // 引用消失时继续显示上一次的内容，收起动画才不会空白
    val lastQuote = remember { arrayOfNulls<Quote>(1) }
    if (quote != null) lastQuote[0] = quote
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlassCircleButton(
            backdrop = backdrop,
            icon = Icons.Rounded.Add,
            contentDescription = "添加图片",
            onClick = onAttach,
            size = 52.dp,
            tint = MaterialTheme.colorScheme.onSurface,
        )
        GlassSurface(
            backdrop = backdrop,
            shape = InputShape,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp),
        ) {
            // animateContentSize 会按矩形裁剪，放在玻璃里面，避免把玻璃的投影裁成方角
            Column(Modifier.animateContentSize(spring(dampingRatio = 0.8f, stiffness = 500f))) {
                AnimatedVisibility(quote != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    lastQuote[0]?.let { QuotePreview(it, onClearQuote) }
                }
                AnimatedVisibility(attachments.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    AttachmentRow(attachments, onRemoveAttachment)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (text.isEmpty()) {
                            Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        BasicTextField(
                            value = text,
                            onValueChange = onTextChange,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default),
                            keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                        )
                    }
                    SendStopButton(mode, canSend, onSend, onStop, Modifier.padding(end = 6.dp, bottom = 6.dp))
                }
            }
        }
    }
}

/** 输入框顶部的引用预览：正在针对哪段回复追问，右边可以取消。 */
@Composable
private fun QuotePreview(quote: Quote, onClear: () -> Unit) {
    Row(
        Modifier.padding(start = 10.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.Reply,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        QuoteBlock(
            quote = quote,
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = null,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        IconButton(onClick = onClear, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "取消引用", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SendStopButton(mode: SendButtonMode, canSend: Boolean, onSend: () -> Unit, onStop: () -> Unit, modifier: Modifier) {
    AnimatedContent(
        targetState = mode,
        transitionSpec = { (scaleIn(spring(dampingRatio = 0.55f)) + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
        modifier = modifier,
        label = "send-stop",
    ) { current ->
        when (current) {
            SendButtonMode.Send -> FilledIconButton(
                onClick = onSend,
                enabled = canSend,
                colors = IconButtonDefaults.filledIconButtonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                ),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "发送", modifier = Modifier.size(20.dp))
            }
            SendButtonMode.Stop -> FilledIconButton(
                onClick = onStop,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Rounded.Stop, contentDescription = "停止", modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun AttachmentRow(attachments: List<ImageRef>, onRemove: (ImageRef) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp),
    ) {
        items(attachments, key = { it.id }) { image ->
            Box {
                ImagePlaceholder(image, Modifier.size(64.dp), RoundedCornerShape(16.dp))
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(onClick = { onRemove(image) }, modifier = Modifier.size(20.dp)) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "移除图片",
                            tint = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 只读会话（CLI/TUI/Telegram 创建）：悬浮玻璃提示条，替代输入栏。 */
@Composable
fun ReadOnlyBar(backdrop: Backdrop, sourceLabel: String, onFork: () -> Unit) =
    NoticeBar(backdrop, "来自 $sourceLabel，只能查看", actionLabel = "分叉后继续", onAction = onFork)

/** 替代输入栏的悬浮提示条（只读会话、当前连接还不能发消息等）。 */
@Composable
fun NoticeBar(backdrop: Backdrop, text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    GlassSurface(
        backdrop = backdrop,
        shape = CircleShape,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            )
            if (actionLabel != null) {
                FilledTonalButton(onClick = onAction) { Text(actionLabel) }
            } else {
                Spacer(Modifier.size(width = 10.dp, height = 40.dp))
            }
        }
    }
}
