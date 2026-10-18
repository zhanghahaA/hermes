package com.hermes.mobile.ui.capabilities

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.model.Skill
import com.hermes.mobile.data.model.Toolset
import com.hermes.mobile.ui.components.IconBadge
import com.hermes.mobile.ui.components.SectionHeader
import com.hermes.mobile.ui.components.TagLabel

/** 技能：按分类分组、可以搜索；点开看说明，可以直接在新对话里用。 */
@Composable
internal fun SkillsTab(repository: HermesRepository, contentPadding: PaddingValues, onUseSkill: (Skill) -> Unit) {
    val skills by repository.skills.collectAsState()
    val error by repository.skillsError.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var opened by remember { mutableStateOf<Skill?>(null) }
    LaunchedEffect(Unit) { repository.refreshCapabilities() }
    val shown = remember(skills, query) {
        val q = query.trim()
        if (q.isEmpty()) skills else skills.filter { it.name.contains(q, true) || it.description.contains(q, true) || it.category.contains(q, true) }
    }
    LazyColumn(contentPadding = contentPadding) {
        item(key = "intro") {
            Text(
                "Hermes 已安装的技能，聊天时它会按需要自动选用；也可以点开一个技能，指定在新对话里用它。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        error?.let { message ->
            item(key = "error") { ProblemCard(message) }
        }
        if (skills.size > 6) {
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索技能") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        if (skills.isEmpty() && error == null) {
            item(key = "empty") {
                Text(
                    "这台 Hermes 还没有安装技能。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        shown.groupBy { it.category.ifBlank { "其他" } }.forEach { (category, group) ->
            item(key = "h-$category") { SectionHeader("$category · ${group.size}", Modifier.padding(start = 4.dp)) }
            items(group, key = { "s-" + it.name }) { skill ->
                ListItem(
                    leadingContent = { IconBadge(Icons.Rounded.AutoAwesome) },
                    headlineContent = { Text(skill.name) },
                    supportingContent = {
                        if (skill.description.isNotBlank()) Text(skill.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    modifier = Modifier.clickable { opened = skill },
                )
            }
        }
    }
    opened?.let { skill ->
        SkillSheet(skill, onUse = { opened = null; onUseSkill(skill) }, onDismiss = { opened = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillSheet(skill: Skill, onUse: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SkillSheetContent(skill, onUse)
    }
}

@Composable
internal fun SkillSheetContent(skill: Skill, onUse: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.AutoAwesome, size = 48.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(skill.name, style = MaterialTheme.typography.titleLarge)
                if (skill.category.isNotBlank()) Text(skill.category, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(skill.description.ifBlank { "（这个技能没有写说明）" }, style = MaterialTheme.typography.bodyLarge)
        Text(
            "Hermes 平时会自己判断什么时候用哪个技能。点下面的按钮会新建一个对话，输入框里写好 /${skill.name}，你接着写要做的事再发送。在任何对话里直接输入 /技能名 也一样。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onUse, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Rounded.Send, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("在新对话里使用")
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 工具集：Hermes 通过 API 能用哪些工具。能通过 SSH 改配置时可以直接开关。 */
@Composable
internal fun ToolsetsTab(repository: HermesRepository, contentPadding: PaddingValues) {
    val toolsets by repository.toolsets.collectAsState()
    val busy by repository.toolsetsBusy.collectAsState()
    val canToggle = repository.canToggleToolsets()
    LaunchedEffect(Unit) { repository.refreshCapabilities() }
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "intro") {
            Text(
                if (canToggle) "Hermes 回复你时能用的工具。开关会通过 SSH 改 Hermes 的配置，下一条消息起生效。"
                else "Hermes 回复你时能用的工具。要开关它们，在 Hermes 那台机器上运行 hermes tools；给这个连接配上 SSH 后也可以直接在这里开关。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        items(toolsets, key = { it.name }) { toolset ->
            ToolsetCard(toolset, canToggle, toolset.name in busy) { repository.toggleToolset(toolset.name) }
        }
    }
}

@Composable
internal fun ToolsetCard(toolset: Toolset, canToggle: Boolean, busy: Boolean, onToggle: () -> Unit) {
    var expanded by rememberSaveable(toolset.name) { mutableStateOf(false) }
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .animateContentSize(),
    ) {
        Column(
            Modifier
                .clickable { expanded = !expanded }
                .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(toolset.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${toolset.name} · ${toolset.toolCount} 个工具",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    busy -> CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
                    canToggle -> Switch(checked = toolset.enabled, onCheckedChange = { onToggle() })
                    toolset.enabled -> TagLabel(
                        "已启用",
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    else -> TagLabel("未启用")
                }
                Icon(
                    if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            if (toolset.description.isNotBlank()) {
                Text(toolset.description, style = MaterialTheme.typography.bodyMedium)
            }
            if (!toolset.configured) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "还缺 API Key，开了也用不了（在 Hermes 那台机器上运行 hermes tools 配置）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (expanded && toolset.tools.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    toolset.tools.forEach { TagLabel(it) }
                }
            }
        }
    }
}

@Composable
private fun ProblemCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}
