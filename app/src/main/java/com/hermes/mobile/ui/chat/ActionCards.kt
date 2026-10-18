package com.hermes.mobile.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.ui.theme.CodeTextStyle
import com.hermes.mobile.ui.theme.LocalHermesColors

/**
 * 审批卡片：平时完全不打扰，只有 Hermes 需要你拍板时才出现。
 * 处理完之后收起成一行小字，聊天记录保持干净。
 */
@Composable
fun ApprovalCard(item: ChatItem.Approval, onChoose: (String) -> Unit) {
    AnimatedContent(
        targetState = item.resolvedChoice,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "approval",
    ) { resolved ->
        if (resolved != null) {
            val denied = resolved == "deny"
            ResolvedNote(
                icon = if (denied) Icons.Rounded.Block else Icons.Rounded.CheckCircle,
                text = "${resolvedLabel(resolved)} · ${item.summary}",
                tint = if (denied) MaterialTheme.colorScheme.error else LocalHermesColors.current.online,
            )
        } else {
            PendingApproval(item, onChoose)
        }
    }
}

@Composable
private fun PendingApproval(item: ChatItem.Approval, onChoose: (String) -> Unit) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.tertiaryContainer.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, colors.tertiary.copy(alpha = 0.35f)),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardHeader(Icons.Rounded.GppMaybe, "需要你的批准", item.summary, colors.tertiary)
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(LocalHermesColors.current.codeBackground, MaterialTheme.shapes.small)
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        item.command,
                        style = CodeTextStyle,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = expanded,
                        modifier = Modifier
                            .weight(1f)
                            .then(if (expanded) Modifier else Modifier.horizontalScroll(rememberScrollState())),
                    )
                    Icon(
                        if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 服务器给的第一个选项（一般是“允许一次”）用实心按钮，拒绝用描边按钮
                item.choices.asReversed().forEach { choice ->
                    val label = choiceLabel(choice)
                    when (choice) {
                        "deny" -> OutlinedButton(onClick = { onChoose(choice) }) { Text(label) }
                        item.choices.first() -> Button(onClick = { onChoose(choice) }) { Text(label) }
                        else -> FilledTonalButton(onClick = { onChoose(choice) }) { Text(label) }
                    }
                }
            }
        }
    }
}

/** 审批选项 id → 按钮文字。新版本多出来的选项原样显示。 */
internal fun choiceLabel(choice: String): String = when (choice) {
    "once" -> "允许一次"
    "session" -> "本会话允许"
    "always" -> "始终允许"
    "deny" -> "拒绝"
    else -> choice
}

private fun resolvedLabel(choice: String): String = when (choice) {
    "once" -> "已允许一次"
    "session" -> "本会话已允许"
    "always" -> "已始终允许"
    "deny" -> "已拒绝"
    // 在别的设备上处理掉的，App 不知道选了什么
    "" -> "已处理"
    else -> "已选择$choice"
}

/** 追问卡片：给出选项，也可以直接打字回答。 */
@Composable
fun ClarifyCard(item: ChatItem.Clarify, onAnswer: (String) -> Unit) {
    AnimatedContent(targetState = item.answer, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "clarify") { answer ->
        if (answer != null) {
            ResolvedNote(Icons.Rounded.CheckCircle, "你的回答：$answer", MaterialTheme.colorScheme.primary)
        } else {
            PendingClarify(item, onAnswer)
        }
    }
}

@Composable
private fun PendingClarify(item: ChatItem.Clarify, onAnswer: (String) -> Unit) {
    var custom by rememberSaveable(item.id) { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.primaryContainer.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.3f)),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardHeader(Icons.AutoMirrored.Rounded.HelpOutline, "Hermes 想确认一下", item.question, colors.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item.options.forEach { option ->
                    FilledTonalButton(onClick = { onAnswer(option) }) { Text(option) }
                }
            }
            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it },
                placeholder = { Text("或者直接输入…") },
                singleLine = true,
                shape = RoundedCornerShape(50),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (custom.isNotBlank()) onAnswer(custom.trim()) }),
                trailingIcon = {
                    IconButton(onClick = { onAnswer(custom.trim()) }, enabled = custom.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "发送回答")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CardHeader(icon: ImageVector, title: String, subtitle: String, tint: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = tint)
            Text(subtitle, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ResolvedNote(icon: ImageVector, text: String, tint: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
