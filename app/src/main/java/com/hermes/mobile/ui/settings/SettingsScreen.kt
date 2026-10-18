package com.hermes.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.model.ConnectionState
import com.hermes.mobile.data.model.ThemeMode
import com.hermes.mobile.platform.supportsDynamicColor
import com.hermes.mobile.ui.components.IconBadge
import com.hermes.mobile.ui.components.NoInsets
import com.hermes.mobile.ui.components.StatusDot
import com.hermes.mobile.ui.components.connectionLabel
import com.hermes.mobile.ui.components.icon
import com.hermes.mobile.ui.rememberPlaceholder
import com.hermes.mobile.ui.glass.GlassTopBar
import com.hermes.mobile.ui.glass.TransparentBarColors
import com.hermes.mobile.ui.main.LocalBottomBarSpace
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color

@Composable
fun SettingsScreen(
    repository: HermesRepository,
    onEditConnection: (String?) -> Unit,
    onOpenWelcome: () -> Unit,
) {
    val settings by repository.settings.collectAsState()
    val connections by repository.connections.collectAsState()
    val activeId by repository.activeConnectionId.collectAsState()
    val state by repository.connectionState.collectAsState()
    val placeholder = rememberPlaceholder()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val backdrop = rememberLayerBackdrop()
    val bottomSpace = LocalBottomBarSpace.current

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = NoInsets,
        containerColor = Color.Transparent,
        topBar = {
            GlassTopBar(backdrop) {
                LargeTopAppBar(title = { Text("设置") }, colors = TransparentBarColors(), scrollBehavior = scrollBehavior)
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(top = padding.calculateTopPadding(), bottom = bottomSpace + 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsGroup("连接") {
                connections.forEachIndexed { index, profile ->
                    val active = profile.id == activeId
                    SettingsRow(
                        icon = profile.type.icon,
                        title = profile.name,
                        subtitle = "${profile.type.label} · ${profile.host}:${profile.port}",
                        onClick = { onEditConnection(profile.id) },
                        trailing = {
                            if (active) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusDot(state)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (state is ConnectionState.Connected) "当前" else connectionLabel(state),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                    )
                    if (index < connections.lastIndex) GroupDivider()
                }
                GroupDivider()
                SettingsRow(Icons.Rounded.Add, "添加连接", "直连、HTTPS、SSH 隧道或手机本机", onClick = { onEditConnection(null) })
            }

            SettingsGroup("外观") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.DarkMode)
                        Spacer(Modifier.width(16.dp))
                        Text("主题", style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(12.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = settings.themeMode == mode,
                                onClick = { repository.updateSettings { it.copy(themeMode = mode) } },
                                shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                            ) { Text(mode.label) }
                        }
                    }
                }
                GroupDivider()
                SwitchRow(
                    Icons.Rounded.Palette,
                    "跟随壁纸取色",
                    if (supportsDynamicColor) "Material You 动态配色" else "需要 Android 12 及以上",
                    checked = settings.dynamicColor,
                    enabled = supportsDynamicColor,
                    onCheckedChange = { value -> repository.updateSettings { it.copy(dynamicColor = value) } },
                )
                GroupDivider()
                SettingsRow(Icons.Rounded.TextFields, "聊天字号", "标准", onClick = { placeholder("聊天字号") })
            }

            SettingsGroup("聊天") {
                SwitchRow(
                    Icons.AutoMirrored.Rounded.Subject,
                    "逐字显示回复",
                    "关闭时回复整条出现，像收到一条消息",
                    checked = settings.streamReplies,
                    onCheckedChange = { value -> repository.updateSettings { it.copy(streamReplies = value) } },
                )
                GroupDivider()
                SwitchRow(
                    Icons.Rounded.MoreHoriz,
                    "显示“正在输入”",
                    "Hermes 处理时显示跳动的三个点",
                    checked = settings.showTyping,
                    onCheckedChange = { value -> repository.updateSettings { it.copy(showTyping = value) } },
                )
                GroupDivider()
                SwitchRow(
                    Icons.AutoMirrored.Rounded.KeyboardReturn,
                    "回车发送",
                    "关闭时回车换行，点按钮发送",
                    checked = settings.enterToSend,
                    onCheckedChange = { value -> repository.updateSettings { it.copy(enterToSend = value) } },
                )
            }

            SettingsGroup("通知") {
                SwitchRow(
                    Icons.Rounded.Notifications,
                    "回复完成时通知",
                    "App 在后台时，Hermes 回复后提醒你",
                    checked = settings.notifyOnReply,
                    onCheckedChange = { value -> repository.updateSettings { it.copy(notifyOnReply = value) } },
                )
            }

            SettingsGroup("高级") {
                SettingsRow(Icons.Rounded.Person, "Hermes Profile", "default", onClick = { placeholder("Profile 切换") })
                GroupDivider()
                SwitchRow(
                    Icons.Rounded.BugReport,
                    "诊断模式",
                    "在聊天菜单里查看原始事件，排查问题时用",
                    checked = settings.diagnostics,
                    onCheckedChange = { value -> repository.updateSettings { it.copy(diagnostics = value) } },
                )
                GroupDivider()
                SettingsRow(Icons.Rounded.RocketLaunch, "重新运行连接向导", onClick = onOpenWelcome)
            }

            SettingsGroup("关于") {
                SettingsRow(Icons.Rounded.Info, "Hermes 移动版", "0.1.0 · 界面骨架", trailing = null)
                GroupDivider()
                SettingsRow(Icons.Rounded.AutoAwesome, "服务器版本", "Hermes Agent v0.9.2（示例）", trailing = null)
            }

            TextButton(onClick = { placeholder("导出诊断日志") }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)) {
                Text("导出诊断日志")
            }
        }
    }
}
