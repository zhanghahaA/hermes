package com.hermes.mobile.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.model.ImageRef

enum class SendButtonMode { Send, Stop }

@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    attachments: List<ImageRef>,
    onRemoveAttachment: (ImageRef) -> Unit,
    onAttach: () -> Unit,
    mode: SendButtonMode,
    onSend: () -> Unit,
    onStop: () -> Unit,
    enterToSend: Boolean,
    placeholder: String = "给 Hermes 发消息",
) {
    val canSend = text.isNotBlank() || attachments.isNotEmpty()
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
        ) {
            AnimatedVisibility(attachments.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
                ) {
                    items(attachments, key = { it.id }) { image ->
                        Box {
                            ImagePlaceholder(image, Modifier.size(64.dp), RoundedCornerShape(12.dp))
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(3.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.75f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                IconButton(onClick = { onRemoveAttachment(image) }, modifier = Modifier.size(20.dp)) {
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
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onAttach, modifier = Modifier.padding(bottom = 4.dp)) {
                    Icon(Icons.Rounded.Add, contentDescription = "添加图片")
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
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
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Box(Modifier.padding(start = 6.dp, bottom = 0.dp)) {
                    AnimatedContent(
                        targetState = mode,
                        transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
                        label = "send-stop",
                    ) { current ->
                        when (current) {
                            SendButtonMode.Send -> FilledIconButton(
                                onClick = onSend,
                                enabled = canSend,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "发送")
                            }
                            SendButtonMode.Stop -> FilledIconButton(
                                onClick = onStop,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ),
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Rounded.Stop, contentDescription = "停止")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 只读会话（CLI/TUI/Telegram 创建）底部替代输入栏的提示。 */
@Composable
fun ReadOnlyBar(sourceLabel: String, onFork: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(
                "这个会话来自 $sourceLabel，手机上只能查看",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            FilledTonalButton(onClick = onFork) { Text("分叉后继续") }
        }
    }
}
