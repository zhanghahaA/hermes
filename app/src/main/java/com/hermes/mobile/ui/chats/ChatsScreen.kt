package com.hermes.mobile.ui.chats

import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import com.hermes.mobile.data.model.ProjectInfo

import com.hermes.mobile.data.model.SessionGrouping

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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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

private enum class ChatFilter(val label: String) { All("全部"), Unread("未读"), Pinned("置顶"), External("其他来源"), Archived("已归档") }

@Composable
fun ChatsScreen(
    repository: HermesRepository,
    onOpenChat: (String) -> Unit,
    onManageConnections: () -> Unit,
    /** 选中的分组；null 是全部。放在外面是因为选中项目时，右下角的“新对话”要建在这个项目里。 */
    group: String? = null,
    onGroupChange: (String?) -> Unit = {},
) {
    val sessions by repository.sessions.collectAsState()
    val drafts by repository.drafts.collectAsState()
    val connections by repository.connections.collectAsState()
    val activeId by repository.activeConnectionId.collectAsState()
    val connectionState by repository.connectionState.collectAsState()
    val active = connections.firstOrNull { it.id == activeId }
    val bots by repository.bots.collectAsState()
    val activeBotName by repository.activeBot.collectAsState()
    val activeBotLabel = activeBotName?.takeIf { it != "default" }?.let { name -> bots?.bots?.firstOrNull { it.name == name }?.label ?: name }

    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ChatFilter.All) }
    val grouping by repository.sessionGroups.collectAsState()
    val archived by repository.archivedSessions.collectAsState()
    // 有 SSH 才能建项目、移动对话
    val admin = remember(activeId, connectionState) { repository.admin() }
    val showGroups = grouping.groups.any { it != SessionGrouping.DefaultGroup } || admin != null
    // 分组没了（换了连接）就回到全部
    LaunchedEffect(group, grouping.groups) { if (group != null && group !in grouping.groups) onGroupChange(null) }
    var refreshing by remember { mutableStateOf(false) }
    var showSwitcher by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<Session?>(null) }
    var openRow by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf(setOf<String>()) }
    var renameTarget by remember { mutableStateOf<Session?>(null) }
    var moveTarget by remember { mutableStateOf<Session?>(null) }
    var projectAction by remember { mutableStateOf<ProjectAction?>(null) }
    // 新建项目；带着会话时，建好就把它移进去
    var newProjectFor by remember { mutableStateOf<NewProjectRequest?>(null) }

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val backdrop = rememberLayerBackdrop()
    val bottomSpace = LocalBottomBarSpace.current
    val pullState = rememberPullToRefreshState()
    // 列表一滚动就收起展开的那一行
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) openRow = null }

    val source = if (filter == ChatFilter.Archived) archived else sessions
    val visible = source
        .filter { it.id !in pendingDelete }
        .filter { query.isBlank() || it.title.contains(query, true) || it.preview.contains(query, true) }
        .filter { group == null || grouping.groupOf(it.id) == group }
        .filter {
            when (filter) {
                ChatFilter.All -> true
                ChatFilter.Unread -> it.unread > 0
                ChatFilter.Pinned -> it.pinned
                ChatFilter.External -> it.external
                ChatFilter.Archived -> true
            }
        }
        .sortedWith(compareByDescending<Session> { it.pinned && filter != ChatFilter.Archived }.thenByDescending { it.updatedAt })

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

    fun archive(session: Session) {
        repository.setArchived(session.id, true)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar("已归档「${session.title}」", actionLabel = "撤销", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) repository.setArchived(session.id, false)
        }
    }

    fun unarchive(session: Session) {
        repository.setArchived(session.id, false)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar("「${session.title}」回到了聊天列表", duration = SnackbarDuration.Short)
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
                            // 用的不是默认 bot 时，标出正在和哪个 bot 聊
                            name = (active?.name ?: "未连接") + (activeBotLabel?.let { " · $it" } ?: ""),
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
                if (!refreshing) {
                    refreshing = true
                    scope.launch {
                        try { repository.refreshSessions() }
                        finally { refreshing = false }
                    }
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
                            (connectionState as ConnectionState.Failed).reason +
                                if (sessions.isNotEmpty()) "\n下面是上次保存的内容，连上后自动更新。" else "",
                            onRetry = { activeId?.let(repository::switchConnection) },
                        )
                    }
                }
                item(key = "search") {
                    SearchField(query, onQueryChange = { query = it })
                }
                if (showGroups) {
                    item(key = "groups") {
                        GroupChips(
                            groups = grouping.groups,
                            counts = sessions.groupingBy { grouping.groupOf(it.id) }.eachCount(),
                            selected = group,
                            onSelect = onGroupChange,
                            onNewProject = admin?.let { { newProjectFor = NewProjectRequest(null) } },
                        )
                    }
                    // 选中的是项目：显示它的文件夹，可以改名、删除
                    val selectedProject = grouping.projects.firstOrNull { it.name == group }
                    if (selectedProject != null && admin != null) {
                        item(key = "project-bar") {
                            ProjectBar(
                                project = selectedProject,
                                onRename = { projectAction = ProjectAction.Rename(selectedProject) },
                                onDelete = { projectAction = ProjectAction.Delete(selectedProject) },
                            )
                        }
                    }
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
                        if (filter == ChatFilter.Archived && archived.isEmpty()) {
                            EmptyState(Icons.Rounded.Archive, "没有归档的对话", "在聊天列表里左滑一个对话，点“归档”，它就会收到这里。")
                        } else if (sessions.isEmpty()) {
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
                        actions = listOfNotNull(
                            if (filter == ChatFilter.Archived) {
                                SwipeAction(
                                    "取消归档",
                                    Icons.Rounded.Unarchive,
                                    MaterialTheme.colorScheme.tertiary,
                                    MaterialTheme.colorScheme.onTertiary,
                                ) { unarchive(session) }
                            } else {
                                SwipeAction(
                                    if (session.pinned) "取消置顶" else "置顶",
                                    Icons.Rounded.PushPin,
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    MaterialTheme.colorScheme.onSecondaryContainer,
                                ) { repository.togglePin(session.id) }
                            },
                            if (filter == ChatFilter.Archived) null else SwipeAction(
                                "归档",
                                Icons.Rounded.Archive,
                                MaterialTheme.colorScheme.tertiary,
                                MaterialTheme.colorScheme.onTertiary,
                            ) { archive(session) },
                            SwipeAction(
                                "删除",
                                Icons.Rounded.Delete,
                                MaterialTheme.colorScheme.error,
                                MaterialTheme.colorScheme.onError,
                            ) { delete(session) },
                        ),
                        modifier = Modifier.animateItem(),
                    ) {
                        SessionRow(
                            session,
                            onClick = { onOpenChat(session.id) },
                            onLongPress = { actionTarget = session },
                            draft = drafts[session.id],
                            // 看全部时，标出不在“默认”里的会话属于哪个分组
                            group = grouping.groupOf(session.id).takeIf { showGroups && group == null && it != SessionGrouping.DefaultGroup },
                        )
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
        val isArchived = archived.any { it.id == session.id }
        SessionActionsSheet(
            session = session,
            archived = isArchived,
            onDismiss = { actionTarget = null },
            onTogglePin = { repository.togglePin(session.id) },
            onRename = { renameTarget = session },
            onToggleArchive = { if (isArchived) unarchive(session) else archive(session) },
            onMove = if (admin != null && !isArchived) ({ moveTarget = session }) else null,
            onDelete = { delete(session) },
        )
    }

    moveTarget?.let { session ->
        val current = grouping.of[session.id]
        ProjectPickerSheet(
            title = session.title,
            projects = grouping.projects,
            current = current,
            onPick = { project ->
                repository.moveToProject(session.id, project)
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(if (project == null) "已移出项目" else "已移到「${project.name}」", duration = SnackbarDuration.Short)
                }
            },
            onNewProject = { newProjectFor = NewProjectRequest(session) },
            onDismiss = { moveTarget = null },
        )
    }

    if (admin != null) when (val action = projectAction) {
        is ProjectAction.Rename -> RenameDialog(
            initial = action.project.name,
            title = "重命名项目",
            onDismiss = { projectAction = null },
            onConfirm = { newName ->
                projectAction = null
                scope.launch {
                    try {
                        admin.renameProject(action.project.id, newName)
                        repository.refreshGroups()
                        onGroupChange(newName)
                    } catch (e: com.hermes.mobile.data.remote.AdminException) {
                        snackbar.showSnackbar("没能改名：${e.message}")
                    }
                }
            },
        )
        is ProjectAction.Delete -> androidx.compose.material3.AlertDialog(
            onDismissRequest = { projectAction = null },
            title = { Text("删除项目「${action.project.name}」？") },
            text = { Text("只删除项目本身（和电脑版的删除一样）。里面的对话不会删，会回到按文件夹分组；电脑上的文件夹和文件也都还在。") },
            confirmButton = {
                TextButton(onClick = {
                    projectAction = null
                    scope.launch {
                        try {
                            admin.deleteProject(action.project.id)
                            onGroupChange(null)
                            repository.refreshGroups()
                            snackbar.showSnackbar("已删除项目「${action.project.name}」")
                        } catch (e: com.hermes.mobile.data.remote.AdminException) {
                            snackbar.showSnackbar("没能删除：${e.message}")
                        }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { projectAction = null }) { Text("取消") } },
        )
        null -> Unit
    }

    if (admin != null) newProjectFor?.let { request ->
        NewProjectDialog(
            admin = admin,
            onDismiss = { newProjectFor = null },
            onCreated = { name, folder ->
                newProjectFor = null
                scope.launch {
                    repository.refreshGroups()
                    val project = repository.sessionGroups.value.projects.firstOrNull { it.name == name } ?: ProjectInfo("", name, folder)
                    if (request.session != null) {
                        repository.moveToProject(request.session.id, project)
                        snackbar.showSnackbar("已新建项目「$name」，并把对话移了进去", duration = SnackbarDuration.Short)
                    } else {
                        onGroupChange(name)
                        snackbar.showSnackbar("已新建项目「$name」，点右下角就在里面开始新对话", duration = SnackbarDuration.Short)
                    }
                }
            },
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
fun SessionRow(session: Session, onClick: () -> Unit, onLongPress: () -> Unit, draft: String? = null, group: String? = null) {
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
                if (group != null) {
                    Spacer(Modifier.width(6.dp))
                    TagLabel(
                        group,
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (session.external) {
                    Spacer(Modifier.width(6.dp))
                    TagLabel(if (session.readOnly) "${session.source.label} · 只读" else session.source.label)
                }
            }
            if (session.running) {
                Text("正在回复…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            } else if (!draft.isNullOrBlank()) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.error)) { append("[草稿] ") }
                        append(draft.replace('\n', ' '))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
    archived: Boolean = false,
    onToggleArchive: () -> Unit = {},
    onMove: (() -> Unit)? = null,
) {
    ActionSheet(
        title = session.title,
        onDismiss = onDismiss,
        actions = listOfNotNull(
            if (archived) null else SheetAction(if (session.pinned) "取消置顶" else "置顶", Icons.Rounded.PushPin, onTogglePin),
            SheetAction("重命名", Icons.Rounded.Edit, onRename, enabled = !session.readOnly && !archived),
            onMove?.let { SheetAction("移到项目…", Icons.AutoMirrored.Rounded.DriveFileMove, it) },
            SheetAction(if (archived) "取消归档" else "归档", if (archived) Icons.Rounded.Unarchive else Icons.Rounded.Archive, onToggleArchive),
            SheetAction("删除", Icons.Rounded.Delete, onDelete, destructive = true),
        ),
    )
}

private sealed interface ProjectAction {
    val project: ProjectInfo
    data class Rename(override val project: ProjectInfo) : ProjectAction
    data class Delete(override val project: ProjectInfo) : ProjectAction
}

/** 选中项目时，分组标签下面的一行：项目的文件夹，改名、删除。 */
@Composable
private fun ProjectBar(project: ProjectInfo, onRename: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            project.path ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRename) { Text("重命名") }
        TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
    }
}

/** 要新建项目；[session] 不为空时，建好后把它移进去。 */
private data class NewProjectRequest(val session: Session?)

/** 分组（Hermes 的项目 / 工作目录）：“全部”加每个分组，带会话数。 */
@Composable
private fun GroupChips(
    groups: List<String>,
    counts: Map<String, Int>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onNewProject: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 能建项目时，“新建项目”放在最前面，不用滑到最右边去找
        if (onNewProject != null) {
            AssistChip(
                onClick = onNewProject,
                label = { Text("新建项目") },
                leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
            )
        } else {
            Icon(Icons.Rounded.FolderOpen, contentDescription = "分组", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("全部分组") })
        groups.forEach { name ->
            FilterChip(
                selected = selected == name,
                onClick = { onSelect(if (selected == name) null else name) },
                label = { Text("$name · ${counts[name] ?: 0}") },
            )
        }
    }
}
