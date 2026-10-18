package com.hermes.mobile.ui.capabilities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.ui.chat.ModelPickerContent
import com.hermes.mobile.ui.components.IconBadge
import com.hermes.mobile.ui.components.EmptyState
import com.hermes.mobile.data.model.AppFeature
import androidx.compose.material.icons.rounded.Extension
import com.hermes.mobile.ui.components.NoInsets
import com.hermes.mobile.ui.components.TagLabel
import com.hermes.mobile.ui.rememberPlaceholder
import com.hermes.mobile.ui.glass.GlassTopBar
import com.hermes.mobile.ui.glass.TransparentBarColors
import com.hermes.mobile.ui.main.LocalBottomBarSpace
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color

private val tabs = listOf("模型", "技能", "工具集", "更多")

@Composable
fun CapabilitiesScreen(repository: HermesRepository, initialTab: Int = 0) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    val backdrop = rememberLayerBackdrop()
    val bottomSpace = LocalBottomBarSpace.current
    Scaffold(
        contentWindowInsets = NoInsets,
        containerColor = Color.Transparent,
        topBar = {
            GlassTopBar(backdrop) {
                TopAppBar(title = { Text("能力") }, colors = TransparentBarColors())
                PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                    tabs.forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                    }
                }
            }
        },
    ) { padding ->
        val contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = bottomSpace + 8.dp)
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(MaterialTheme.colorScheme.background),
        ) {
            val features by repository.features.collectAsState()
            val needed = when (tab) {
                0 -> AppFeature.Models
                1 -> AppFeature.Skills
                2 -> AppFeature.Toolsets
                else -> null
            }
            when {
                needed != null && needed !in features -> Box(Modifier.padding(contentPadding)) {
                    EmptyState(Icons.Rounded.Extension, "还没接入", "连接真实 Hermes 后的${tabs[tab]}列表会在后续阶段完成。")
                }
                tab == 0 -> ModelsTab(repository, contentPadding)
                tab == 1 -> SkillsTab(repository, contentPadding)
                tab == 2 -> ToolsetsTab(repository, contentPadding)
                else -> MoreTab(contentPadding)
            }
        }
    }
}

@Composable
private fun ModelsTab(repository: HermesRepository, contentPadding: PaddingValues) {
    val models by repository.models.collectAsState()
    val defaultId by repository.defaultModelId.collectAsState()
    val current = models.firstOrNull { it.id == defaultId }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(contentPadding)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    Icons.Rounded.Psychology,
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    size = 48.dp,
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("默认模型", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(current?.name ?: "—", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        "${current?.provider ?: ""} · 新对话默认使用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        ModelPickerContent(
            models = models,
            selectedName = current?.name,
            onSelect = { repository.setDefaultModel(it.id) },
            title = null,
        )
    }
}

@Composable
private fun SkillsTab(repository: HermesRepository, contentPadding: PaddingValues) {
    val skills by repository.skills.collectAsState()
    LazyColumn(contentPadding = contentPadding) {
        item {
            Text(
                "Hermes 已安装的技能，聊天时它会自动选用。安装和删除需要在电脑上操作。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        items(skills, key = { it.name }) { skill ->
            ListItem(
                leadingContent = { IconBadge(Icons.Rounded.AutoAwesome) },
                headlineContent = { Text(skill.name) },
                supportingContent = { Text(skill.description) },
                trailingContent = { TagLabel(skill.category) },
            )
        }
    }
}

@Composable
private fun ToolsetsTab(repository: HermesRepository, contentPadding: PaddingValues) {
    val toolsets by repository.toolsets.collectAsState()
    LazyColumn(contentPadding = contentPadding) {
        items(toolsets, key = { it.name }) { toolset ->
            ListItem(
                leadingContent = { IconBadge(Icons.Rounded.Build) },
                headlineContent = { Text(toolset.name) },
                supportingContent = { Text("${toolset.description} · ${toolset.toolCount} 个工具") },
                trailingContent = { Switch(checked = toolset.enabled, onCheckedChange = { repository.toggleToolset(toolset.name) }) },
            )
        }
    }
}

/** API Server 管不到、需要通过 SSH 执行 hermes 命令的功能（计划 M5）。 */
@Composable
private fun MoreTab(contentPadding: PaddingValues) {
    val placeholder = rememberPlaceholder()
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "下面这些功能 API Server 没有提供接口，计划在 SSH 连接下通过远程执行 hermes 命令来实现。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MoreItem(Icons.Rounded.Memory, "记忆", "查看和整理 Hermes 记住的事情") { placeholder("记忆管理") }
        MoreItem(Icons.Rounded.Hub, "MCP 服务器", "添加、启用或停用 MCP 服务器") { placeholder("MCP 管理") }
        MoreItem(Icons.Rounded.Dns, "Gateway", "查看 gateway 状态、重启服务") { placeholder("Gateway 管理") }
        MoreItem(Icons.Rounded.Terminal, "配置文件", "编辑 config.yaml 和 .env") { placeholder("配置编辑") }
    }
}

@Composable
private fun MoreItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            leadingContent = { IconBadge(icon) },
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            trailingContent = { TagLabel("需要 SSH") },
        )
    }
}
