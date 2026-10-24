package com.hermes.mobile.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.model.ActivityStatus
import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.WorkStep

/** 过程和公开回复分开，收起过程不影响回复；不提供思考内容的服务器只显示实际工具步骤。 */
@Composable
fun WorkActivityRow(activity: ChatItem.Activity, maxWidth: Dp, showProcess: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        if (showProcess) {
            var expanded by rememberSaveable(activity.id) { mutableStateOf(true) }
            val status = when (activity.status) {
                ActivityStatus.Working -> "正在工作"
                ActivityStatus.Completed -> "工作过程"
                ActivityStatus.Stopped -> "已停止"
                ActivityStatus.Failed -> "执行出错"
            }
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().testTag("work-process-${activity.id}")
                        .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                        .clickable { expanded = !expanded }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text(status, style = MaterialTheme.typography.labelLarge)
                    Text("${activity.steps.size} 个步骤", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        if (expanded) "收起工作过程" else "展开工作过程", modifier = Modifier.size(20.dp))
                }
                AnimatedVisibility(expanded) {
                    Column(
                        Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (activity.steps.isEmpty()) Text(
                            if (activity.status == ActivityStatus.Working) "等待 Hermes 提供思考或工具过程…" else "服务器没有提供可展示的思考或工具过程",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        activity.steps.forEach { step ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val suffix = if (step.kind != WorkStep.Kind.Tool) "" else when {
                                    step.failed -> " · 失败"
                                    step.finished -> " · 已完成"
                                    activity.status == ActivityStatus.Working -> " · 执行中"
                                    else -> " · 未收到完成事件"
                                }
                                Text(step.title + suffix, style = MaterialTheme.typography.labelMedium,
                                    color = if (step.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                if (step.text.isNotBlank()) SelectionContainer {
                                    Text(step.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Text("仅显示服务器返回的过程 · 最多保留最近 100 个步骤", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (activity.earlierReply.isNotBlank()) StreamingRow(activity.earlierReply, maxWidth, showProcess)
    }
}
