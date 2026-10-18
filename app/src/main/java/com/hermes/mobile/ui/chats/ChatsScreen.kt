package com.hermes.mobile.ui.chats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Badge
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import com.hermes.mobile.ui.glass.GlassTopBar
import com.hermes.mobile.ui.glass.TransparentBarColors
import com.hermes.mobile.ui.main.LocalBottomBarSpace
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.model.ConnectionState
import com.hermes.mobile.data.model.Session
import com.hermes.mobile.ui.components.ConnectionPill
import com.hermes.mobile.ui.components.EmptyState
import com.hermes.mobile.ui.components.NoInsets
import com.hermes.mobile.ui.components.SessionAvatar
import com.hermes.mobile.ui.components.SwipeAction
import com.hermes.mobile.ui.components.SwipeActionsRow
import com.hermes.mobile.ui.components.TagLabel
import com.hermes.mobile.ui.components.listTime
import kotlinx.coroutines.launch

private enum class ChatFilter(val label: String) { All("全部"), Unread("未读"), Pinned("置顶"), External("其他来源") }

@Composable
fun ChatsScreen(
    repository: HermesRepository,
    onOpenChat: (String) -> Unit,
    onManageConnections: () -> Unit,
) {
    val sessions by repository.sessions.collectAsState()
    val connections by repository.connections.collectAsState()
    val activeId by repository.activeConnectionId.collectAsState()
    val connectionState by repository.connectionState.collectAsState()
    val active = connections.firstOrNull { it.id == activeId }

    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ChatFilter.All) }
    var refreshing by remember { mutableStateOf(false) }
    var showSwitcher by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<Session?>(null) }
    var openRow by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf(setOf<String>()) }
    var renameTarget by remember { mutableStateOf<Session?>(null) }

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val backdrop = rememberLayerBackdrop()
    val bottomSpace = LocalBottomBarSpace.current
    val pullState = rememberPullToRefreshState()
    // 列表一滚动就收起展开的那一行
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) openRow = null }

    val visible = sessions
        .filter { it.id !in pendingDelete }
        .filter { query.isBlank() || it.title.contains(query, true) || it.preview.contains(query, true) }
        .filter {
            when (filter) {
                ChatFilter.All -> true
                ChatFilter.Unread -> it.unread > 0
                ChatFilter.Pinned -> it.pinned
                ChatFilter.External -> it.external
            }
        }
        .sortedWith(compareByDescending<Session> { it.pinned }.thenByDescending { it.updatedAt })

    // 删除先只在界面上隐藏，提示条消失（没点撤销）才真正删除——服务器上的删除是撤销不了的
    fun delete(session: Session) {
        pendingDelete = pendingDelete + session.id
        scope.launch {
            var undone = false
            try {
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar("已删除「${session.title}」", actionLabel = "撤销", duration = SnackbarDuration.Short)
                undone = result == SnackbarResult.ActionPerformed
            } finally {
                if (!undone) repository.deleteSession(session.id)
                pendingDelete = pendingDelete - session.id
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = NoInsets,
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = bottomSpace)) },
        topBar = {
            GlassTopBar(backdrop) {
                LargeTopAppBar(
                    title = { Text("聊天") },
                    actions = {
                        ConnectionPill(
                            name = active?.name ?: "未连接",
                            state = connectionState,
                            onClick = { showSwitcher = true },
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    },
                    colors = TransparentBarColors(),
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    repository.refreshSessions()
                    refreshing = false
                }
            },
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = refreshing,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = padding.calculateTopPadding()),
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(MaterialTheme.colorScheme.background),
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 4.dp, bottom = bottomSpace + 16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (connectionState is ConnectionState.Failed) {
                    item(key = "offline") {
                        OfflineBanner(
                            (connectionState as ConnectionState.Failed).reason,
                            onRetry = { activeId?.let(repository::switchConnection) },
                        )
                    }
                }
                item(key = "search") {
                    SearchField(query, onQueryChange = { query = it })
                }
                item(key = "filters") {
                    Row(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ChatFilter.entries.forEach { item ->
                            FilterChip(
                                selected = filter == item,
                                onClick = { filter = item },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
                if (visible.isEmpty()) {
                    item(key = "empty") {
                        if (sessions.isEmpty()) {
                            EmptyState(
                                Icons.Rounded.Forum,
                                "还没有对话",
                                "点右下角的按钮开始新对话，像发消息一样和 Hermes 聊天。",
                            )
                        } else {
                            EmptyState(Icons.Rounded.SearchOff, "没有找到匹配的会话", "换个关键词，或者切换上面的筛选条件。")
                        }
                    }
                }
                items(visible, key = { it.id }) { session ->
                    SwipeActionsRow(
                        isOpen = openRow == session.id,
                        onOpenChange = { open -> openRow = if (open) session.id else null },
                        actions = listOf(
                            SwipeAction(
                                if (session.pinned) "取消置顶" else "置顶",
                                Icons.Rounded.PushPin,
                                MaterialTheme.colorScheme.secondaryContainer,
                                MaterialTheme.colorScheme.onSecondaryContainer,
                            ) { repository.togglePin(session.id) },
                            SwipeAction(
                                "删除",
                                Icons.Rounded.Delete,
                                MaterialTheme.colorScheme.error,
                                MaterialTheme.colorScheme.onError,
                            ) { delete(session) },
                        ),
                        modifier = Modifier.animateItem(),
                    ) {
                        SessionRow(session, onClick = { onOpenChat(session.id) }, onLongPress = { actionTarget = session })
                    }
                }
            }
        }
    }

    if (showSwitcher) {
        ConnectionSwitcherSheet(
            connections = connections,
            activeId = activeId,
            state = connectionState,
            onSelect = {
                repository.switchConnection(it)
                showSwitcher = false
            },
            onManage = {
                showSwitcher = false
                onManageConnections()
            },
            onDismiss = { showSwitcher = false },
        )
    }

    actionTarget?.let { session ->
        SessionActionsSheet(
            session = session,
            onDismiss = { actionTarget = null },
            onTogglePin = { repository.togglePin(session.id) },
            onRename = { renameTarget = session },
            onDelete = { delete(session) },
        )
    }

    renameTarget?.let { session ->
        RenameDialog(
            initial = session.title,
            onDismiss = { renameTarget = null },
            onConfirm = {
                repository.renameSession(session.id, it)
                renameTarget = null
            },
        )
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .height(48.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("搜索会话", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AnimatedVisibility(query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "清空", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun OfflineBanner(reason: String, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WifiOff, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(reason, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
fun SessionRow(session: Session, onClick: () -> Unit, onLongPress: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SessionAvatar(session.title, size = 52.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (session.external) {
                    Spacer(Modifier.width(6.dp))
                    TagLabel(if (session.readOnly) "${session.source.label} · 只读" else session.source.label)
                }
            }
            if (session.running) {
                Text("正在回复…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            } else {
                Text(
                    session.preview.ifEmpty { "暂无消息" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (session.unread > 0) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                listTime(session.updatedAt),
                style = MaterialTheme.typography.labelMedium,
                color = if (session.unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                session.unread > 0 -> Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("${session.unread}") }
                session.pinned -> Icon(
                    Icons.Rounded.PushPin,
                    contentDescription = "已置顶",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Spacer(Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun SessionActionsSheet(
    session: Session,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    ActionSheet(
        title = session.title,
        onDismiss = onDismiss,
        actions = listOf(
            SheetAction(if (session.pinned) "取消置顶" else "置顶", Icons.Rounded.PushPin, onTogglePin),
            SheetAction("重命名", Icons.Rounded.Edit, onRename, enabled = !session.readOnly),
            SheetAction("删除", Icons.Rounded.Delete, onDelete, destructive = true),
        ),
    )
}
