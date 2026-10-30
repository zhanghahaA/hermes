package com.hermes.mobile.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.model.ActivityStatus
import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.WorkStep

/**
 * 用户点过的展开 / 收起（按 activity id，单步按 "activity id/步骤 id"）。离开聊天页再进来界面状态会清空，
 * 所以记在这里；工作过程本身也只在内存里，App 进程结束就一起没了。
 */
internal object WorkProcessFolds {
    val expanded = mutableStateMapOf<String, Boolean>()
}

/**
 * 过程和公开回复分开，收起过程不影响回复；不提供思考内容的服务器只显示实际工具步骤。
 * 样式参照 Claude：一行摘要，展开后是一条时间线，每步一个图标、一行说明，点开看细节。
 * 没点过时：进行中展开，结束后收起。
 */
@Composable
fun WorkActivityRow(activity: ChatItem.Activity, maxWidth: Dp, showProcess: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        if (showProcess) {
            val working = activity.status == ActivityStatus.Working
            val expanded = WorkProcessFolds.expanded[activity.id] ?: working
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth()) {
                ProcessHeader(activity, expanded) { WorkProcessFolds.expanded[activity.id] = !expanded }
                AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp)) {
                        if (activity.steps.isEmpty()) Text(
                            if (working) "等待 Hermes 提供思考或工具过程…" else "服务器没有提供可展示的思考或工具过程",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 26.dp, top = 4.dp, bottom = 4.dp),
                        )
                        activity.steps.forEachIndexed { i, step ->
                            StepRow(activity, step, last = i == activity.steps.lastIndex)
                        }
                        if (activity.steps.size >= 100) Text(
                            "只保留最近 100 个步骤", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 26.dp, top = 4.dp),
                        )
                    }
                }
            }
        }
        if (activity.earlierReply.isNotBlank()) StreamingRow(activity.earlierReply, maxWidth, showProcess)
    }
}

/** “正在处理 · 第 3 步  ›” / “已完成 5 个步骤 · 用时 32 秒  ⌄”。 */
@Composable
private fun ProcessHeader(activity: ChatItem.Activity, expanded: Boolean, onToggle: () -> Unit) {
    val count = activity.steps.size
    val took = activity.finishedAt?.let { elapsed(it - activity.time) }
    val label = when (activity.status) {
        ActivityStatus.Working -> if (count == 0) "正在思考" else "正在处理 · 第 $count 步"
        ActivityStatus.Completed -> listOfNotNull(if (count == 0) "已完成" else "已完成 $count 个步骤", took).joinToString(" · ")
        ActivityStatus.Stopped -> listOfNotNull("已停止 · $count 个步骤", took).joinToString(" · ")
        ActivityStatus.Failed -> listOfNotNull("执行出错 · $count 个步骤", took).joinToString(" · ")
    }
    val color = if (activity.status == ActivityStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val turn by animateFloatAsState(if (expanded) 90f else 0f, label = "process-chevron")
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onToggle)
            .testTag("work-process-${activity.id}")
            .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (activity.status == ActivityStatus.Working) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        } else {
            Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
        Icon(
            Icons.Rounded.ChevronRight, if (expanded) "收起工作过程" else "展开工作过程",
            tint = color, modifier = Modifier.size(18.dp).rotate(turn),
        )
    }
}

/** 时间线上的一步：左边图标和竖线，右边一行说明；点开看完整内容。 */
@Composable
private fun StepRow(activity: ChatItem.Activity, step: WorkStep, last: Boolean) {
    val key = "${activity.id}/${step.id}"
    val open = WorkProcessFolds.expanded[key] == true
    val running = step.kind == WorkStep.Kind.Tool && !step.finished && activity.status == ActivityStatus.Working
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // 图标 + 往下连到下一步的竖线
        Column(Modifier.width(22.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 9.dp).size(18.dp), contentAlignment = Alignment.Center) {
                Icon(
                    stepIcon(step), null, modifier = Modifier.size(16.dp),
                    tint = if (step.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!last) Box(Modifier.padding(top = 3.dp).width(1.dp).weight(1f).background(line))
        }
        Column(
            Modifier.weight(1f).padding(start = 8.dp, bottom = if (last) 2.dp else 6.dp)
                .clip(RoundedCornerShape(12.dp)).clickable { WorkProcessFolds.expanded[key] = !open }
                .semantics { contentDescription = stepTitle(step) }
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (step.kind) {
                WorkStep.Kind.Reasoning -> {
                    Text("思考", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        step.text.trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                    )
                }
                WorkStep.Kind.Notice -> {
                    Text(step.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    if (step.text.isNotBlank()) Text(step.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                WorkStep.Kind.Tool -> ToolStep(step, open, running)
            }
        }
    }
}

@Composable
private fun ToolStep(step: WorkStep, open: Boolean, running: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stepTitle(step), style = MaterialTheme.typography.labelLarge,
            color = if (step.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        val summary = step.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        Text(
            summary, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            running -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            step.failed -> Text("失败", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            step.durationMs != null -> Text(elapsed(step.durationMs), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            !step.finished -> Text("未收到完成事件", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (open && (step.text.isNotBlank() || step.output.isNotBlank())) SelectionContainer {
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            if (step.text.isNotBlank()) Text(step.text.trim(), style = mono, color = MaterialTheme.colorScheme.onSurface)
            if (step.output.isNotBlank()) {
                if (step.text.isNotBlank()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(step.output.trim(), style = mono, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 30,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 毫秒 → “0.8 秒” / “32 秒” / “2 分 5 秒”。 */
internal fun elapsed(ms: Long): String {
    val seconds = ms / 1000.0
    return when {
        seconds < 10 -> "%.1f 秒".format(seconds.coerceAtLeast(0.1))
        seconds < 60 -> "${seconds.toInt()} 秒"
        else -> "${(seconds / 60).toInt()} 分 ${(seconds % 60).toInt()} 秒"
    }
}

/** Hermes 自带工具的中文说明；不认识的照原名显示。 */
internal fun stepTitle(step: WorkStep): String {
    if (step.kind != WorkStep.Kind.Tool) return step.title
    val name = step.title
    return ToolLabels[name] ?: when {
        name.startsWith("browser_") -> "操作浏览器"
        name.startsWith("ha_") -> "智能家居"
        name.startsWith("mcp_") -> "MCP · ${name.removePrefix("mcp_")}"
        else -> name
    }
}

private val ToolLabels = mapOf(
    "terminal" to "运行命令", "process" to "管理进程", "execute_code" to "运行代码",
    "read_file" to "读取文件", "write_file" to "写入文件", "patch" to "修改文件", "search_files" to "搜索文件",
    "web_search" to "搜索网页", "web_extract" to "读取网页",
    "vision_analyze" to "看图", "image_generate" to "生成图片", "text_to_speech" to "合成语音",
    "delegate_task" to "分派子任务", "mixture_of_agents" to "多模型讨论",
    "todo" to "更新待办", "memory" to "更新记忆", "session_search" to "搜索聊天记录",
    "skills_list" to "查看技能", "skill_view" to "查看技能", "skill_manage" to "管理技能",
    "clarify" to "向你提问", "send_message" to "发送消息", "cronjob" to "定时任务",
)

private fun stepIcon(step: WorkStep): ImageVector = when (step.kind) {
    WorkStep.Kind.Reasoning -> Icons.Rounded.Psychology
    WorkStep.Kind.Notice -> Icons.Rounded.Info
    WorkStep.Kind.Tool -> when {
        step.failed -> Icons.Rounded.ErrorOutline
        step.title in setOf("terminal", "process") -> Icons.Rounded.Terminal
        step.title == "execute_code" -> Icons.Rounded.Code
        step.title in setOf("read_file", "write_file", "patch") -> Icons.Rounded.Description
        step.title in setOf("search_files", "session_search") -> Icons.Rounded.Search
        step.title.startsWith("web_") || step.title.startsWith("browser_") -> Icons.Rounded.Language
        step.title in setOf("vision_analyze", "image_generate") -> Icons.Rounded.Image
        step.title in setOf("delegate_task", "mixture_of_agents") -> Icons.Rounded.Hub
        step.title in setOf("todo", "cronjob") -> Icons.Rounded.Checklist
        step.title == "memory" -> Icons.Rounded.Memory
        step.title.startsWith("skill") -> Icons.Rounded.Extension
        step.title.startsWith("ha_") -> Icons.Rounded.Home
        else -> Icons.Rounded.Build
    }
}
