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
import androidx.compose.material.icons.rounded.DesktopWindows
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
import com.hermes.mobile.data.model.Skill
import com.hermes.mobile.ui.admin.AdminPage
import com.hermes.mobile.ui.chat.ModelPickerContent
import com.hermes.mobile.ui.components.IconBadge
import com.hermes.mobile.ui.components.EmptyState
import com.hermes.mobile.data.model.AppFeature
import androidx.compose.material.icons.rounded.Extension
import com.hermes.mobile.ui.components.NoInsets
import com.hermes.mobile.ui.components.TagLabel
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
fun CapabilitiesScreen(
    repository: HermesRepository,
    initialTab: Int = 0,
    onUseSkill: (Skill) -> Unit = {},
    onOpenAdmin: (AdminPage) -> Unit = {},
) {
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
                    EmptyState(Icons.Rounded.Extension, "看不到${tabs[tab]}", "还没连上 Hermes，或者这台 Hermes 的版本没有提供${tabs[tab]}接口。")
                }
                tab == 0 -> ModelsTab(repository, contentPadding)
                tab == 1 -> SkillsTab(repository, contentPadding, onUseSkill)
                tab == 2 -> ToolsetsTab(repository, contentPadding)
                else -> MoreTab(contentPadding, hasSsh = repository.admin() != null, onOpenAdmin = onOpenAdmin)
            }
        }
    }
}

@Composable
private fun ModelsTab(repository: HermesRepository, contentPadding: PaddingValues) {
    val models by repository.models.collectAsState()
    val defaultId by repository.defaultModelId.collectAsState()
    val current = models.firstOrNull { it.id == defaultId }
    val serverDefault = models.firstOrNull { it.note == "Hermes 默认" }
    ModelPickerContent(
        models = models,
        isSelected = { it.id == defaultId },
        onSelect = { repository.setDefaultModel(it.id) },
        contentPadding = contentPadding,
        header = {
            item(key = "current") {
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
                            Text("新对话默认模型", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(current?.name ?: "—", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(
                                if (current != null && current.id != serverDefault?.id) "${current.provider} · 只影响在手机上新建的对话"
                                else "${current?.provider ?: ""} · 跟随 Hermes 的默认设置",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
            item(key = "hint") {
                Text(
                    "已有的对话在聊天页顶部点模型名单独切换。Hermes 自己的默认模型要在那台机器上用 hermes model 修改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        },
    )
}

/** 管理功能：记忆、MCP、配置文件要经 SSH 读写 Hermes 的文件；Gateway 状态走 API。 */
@Composable
private fun MoreTab(contentPadding: PaddingValues, hasSsh: Boolean, onOpenAdmin: (AdminPage) -> Unit) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (hasSsh) "这些功能 API 管不到，App 经 SSH 在 Hermes 那台机器上读写它的文件、执行 hermes 命令。"
            else "记忆、MCP 和配置文件要经 SSH 读写 Hermes 的文件。给当前连接配上 SSH 就能用（本机 Termux 用 8022 端口的 sshd）。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MoreItem(Icons.Rounded.Memory, "记忆", "看看 Hermes 记住了你什么，改错的、删多余的", needsSsh = !hasSsh) { onOpenAdmin(AdminPage.Memory) }
        MoreItem(Icons.Rounded.Hub, "MCP 服务器", "添加、测试、启用或停用 MCP 服务器", needsSsh = !hasSsh) { onOpenAdmin(AdminPage.Mcp) }
        MoreItem(Icons.Rounded.Dns, "Gateway", "运行状态、接入的平台、今天的用量和日志", needsSsh = false) { onOpenAdmin(AdminPage.Gateway) }
        MoreItem(Icons.Rounded.DesktopWindows, "Bot 屏幕", "看 bot 在服务器桌面上做什么；要登录、填验证码时接管", needsSsh = !hasSsh) { onOpenAdmin(AdminPage.Screen) }
        MoreItem(Icons.Rounded.Terminal, "配置文件", "直接编辑 config.yaml（保存前自动检查格式）", needsSsh = !hasSsh) { onOpenAdmin(AdminPage.Config) }
    }
}

@Composable
private fun MoreItem(icon: ImageVector, title: String, subtitle: String, needsSsh: Boolean, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            leadingContent = { IconBadge(icon) },
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            trailingContent = { if (needsSsh) TagLabel("需要 SSH") },
        )
    }
}
