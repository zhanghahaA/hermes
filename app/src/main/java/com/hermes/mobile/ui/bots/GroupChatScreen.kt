package com.hermes.mobile.ui.bots

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.model.Bot
import com.hermes.mobile.data.model.Room
import com.hermes.mobile.data.model.RoomAction
import com.hermes.mobile.data.model.RoomEvent
import com.hermes.mobile.data.model.RoomStatus
import com.hermes.mobile.data.remote.AdminException
import com.hermes.mobile.ui.chat.ChatInputBar
import com.hermes.mobile.ui.chat.NoticeBar
import com.hermes.mobile.ui.chat.SendButtonMode
import com.hermes.mobile.ui.components.EmptyState
import com.hermes.mobile.ui.components.MarkdownText
import com.hermes.mobile.ui.components.NoInsets
import com.hermes.mobile.ui.components.SessionAvatar
import com.hermes.mobile.ui.components.TypingBubble
import com.hermes.mobile.ui.glass.GlassTopBar
import com.hermes.mobile.ui.glass.TransparentBarColors
import com.hermes.mobile.ui.theme.LocalHermesColors
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 群聊：几个 bot 和你在一个房间里讨论（Hermes 的 hosted room）。
 * 你发一条，@ 到的 bot 回答（没 @ 就都回答），它们之间互相 @ 还会再接几轮，最多 3 轮 / 10 条，都在电脑上的 gateway 里跑。
 * 这里每隔一两秒读一次新记录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(repository: HermesRepository, roomId: String, onBack: () -> Unit) {
    val api = remember { repository.roomApi() }
    val roster by repository.bots.collectAsState()
    val settings by repository.settings.collectAsState()
    val known by repository.rooms.collectAsState()
    var room by remember { mutableStateOf(known?.firstOrNull { it.id == roomId }) }
    var events by remember { mutableStateOf(emptyList<RoomEvent>()) }
    var status by remember { mutableStateOf<RoomStatus?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var waitingSince by remember { mutableStateOf(0L) }
    var mentionMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var disbanding by remember { mutableStateOf(false) }
    var wakeUp by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val bots = roster?.bots.orEmpty()

    // 读新记录：发完消息、有 bot 在回复时读得勤一点
    LaunchedEffect(roomId, api, wakeUp) {
        if (api == null) return@LaunchedEffect
        while (true) {
            try {
                var since = events.lastOrNull()?.seq ?: 0
                do {
                    val log = api.log(roomId, since)
                    if (log.events.isNotEmpty()) {
                        events = (events + log.events).distinctBy { it.seq }.sortedBy { it.seq }
                        since = events.last().seq
                    }
                } while (log.hasMore && log.events.isNotEmpty())
                if (events.any { it.kind == "room.disbanded" }) {
                    error = "这个群聊已经解散了"
                    loaded = true
                    break
                }
                val s = api.status(roomId)
                status = s
                room = s.room
                error = null
            } catch (e: AdminException) {
                error = e.message
            }
            loaded = true
            val busy = status?.working == true || System.currentTimeMillis() - waitingSince < 20_000
            delay(if (busy) 1_500 else 4_000)
        }
    }

    // 有新消息时滚到底
    val visible = remember(events) { events.filter(::shown) }
    LaunchedEffect(visible.size, status?.working) {
        val last = visible.size + (if (status?.working == true) 1 else 0) + (status?.actions?.size ?: 0)
        if (last > 0) listState.animateScrollToItem(last - 1)
    }

    fun send() {
        val body = text.trim()
        if (body.isEmpty() || api == null || sending) return
        sending = true
        scope.launch {
            try {
                api.send(roomId, body)
                text = ""
                waitingSince = System.currentTimeMillis()
                wakeUp++
            } catch (e: AdminException) {
                error = e.message
            }
            sending = false
        }
    }

    fun act(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                wakeUp++
            } catch (e: AdminException) {
                error = e.message
            }
        }
    }

    val backdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    var bottomBarHeight by remember { mutableIntStateOf(0) }
    val bottomBarSpace = with(density) { bottomBarHeight.toDp() }
    val current = room

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            GlassTopBar(backdrop) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回") } },
                    title = {
                        Column {
                            Text(current?.name ?: "群聊", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (current != null) {
                                Text(
                                    current.members.joinToString("、") { memberName(it.profile, it.displayName, it.handle, bots) },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { menu = true }, enabled = current != null) { Icon(Icons.Rounded.MoreVert, contentDescription = "更多") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("改群名") },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                    onClick = { menu = false; renaming = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("解散群聊", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Rounded.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; disbanding = true },
                                )
                            }
                        }
                    },
                    colors = TransparentBarColors(),
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .background(MaterialTheme.colorScheme.background),
            ) {
                val bubbleWidth = maxWidth * 0.8f
                when {
                    api == null -> EmptyState(Icons.Rounded.Groups, "需要 SSH", "群聊在电脑上的 Hermes gateway 里跑，要经 SSH 连过去。", Modifier.align(Alignment.Center))
                    !loaded -> Unit
                    visible.isEmpty() && error == null -> EmptyState(
                        Icons.Rounded.Groups,
                        "说点什么",
                        "发一条消息，群里的 bot 都会回答；@ 某个 bot 就只让它回答。bot 之间互相 @ 会再接着聊几轮。",
                        Modifier.align(Alignment.Center),
                    )
                }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 8.dp, bottom = bottomBarSpace + 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    error?.let { e ->
                        item(key = "error") { Notice(e, isError = true) }
                    }
                    items(visible, key = { it.seq }) { event ->
                        when {
                            event.isMessage && event.fromUser -> UserBubble(event.text, bubbleWidth)
                            event.isMessage -> {
                                val bot = bots.firstOrNull { it.name == (event.actorProfile ?: event.actorId) }
                                val member = current?.members?.firstOrNull { it.memberId == event.actorId || it.profile == event.actorProfile }
                                MemberBubble(
                                    repository = repository,
                                    bot = bot,
                                    name = event.actorName ?: memberName(event.actorProfile ?: event.actorId, member?.displayName, member?.handle, bots),
                                    text = event.text + if (event.attachments > 0) "\n\n（附件 ${event.attachments} 个，在电脑上查看）" else "",
                                    maxWidth = bubbleWidth,
                                )
                            }
                            else -> Notice(noticeText(event, current, bots), isError = event.kind == "turn.failed")
                        }
                    }
                    status?.actions?.forEach { action ->
                        item(key = "action-${action.kind}-${action.taskId}") {
                            val who = current?.members?.firstOrNull { it.memberId == action.memberId }
                            ActionCard(
                                action = action,
                                name = memberName(who?.profile ?: action.memberId, who?.displayName, who?.handle, bots),
                                onApprove = { allow -> act { api?.approve(roomId, action, allow) } },
                                onRetry = { act { api?.retry(roomId, action) } },
                                onDiscard = { act { api?.discard(roomId, action) } },
                            )
                        }
                    }
                    if (status?.working == true) {
                        item(key = "typing") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TypingBubble()
                                Spacer(Modifier.width(8.dp))
                                Text("正在讨论…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { bottomBarHeight = it.height },
            ) {
                when {
                    api == null || current == null && loaded && error != null -> Unit
                    status?.driverRunning == false -> NoticeBar(backdrop, "电脑上 gateway 的群聊服务没在运行，暂时不能发消息")
                    else -> Box {
                        ChatInputBar(
                            backdrop = backdrop,
                            text = text,
                            onTextChange = { text = it },
                            attachments = emptyList(),
                            onRemoveAttachment = {},
                            onAttach = { mentionMenu = true },
                            mode = if (status?.working == true && text.isBlank()) SendButtonMode.Stop else SendButtonMode.Send,
                            onSend = ::send,
                            onStop = { act { api.stop(roomId) } },
                            enterToSend = settings.enterToSend,
                            placeholder = "发到群里，@ 谁就谁来答",
                            attachIcon = Icons.Rounded.AlternateEmail,
                            attachDescription = "@ 某个 bot",
                        )
                        DropdownMenu(expanded = mentionMenu, onDismissRequest = { mentionMenu = false }) {
                            current?.members?.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text("${memberName(m.profile, m.displayName, m.handle, bots)}  @${m.handle}") },
                                    onClick = {
                                        mentionMenu = false
                                        text = mention(text, m.handle)
                                    },
                                )
                            }
                            DropdownMenuItem(text = { Text("所有人  @all") }, onClick = { mentionMenu = false; text = mention(text, "all") })
                        }
                    }
                }
            }
        }
    }

    if (renaming && current != null) {
        var name by remember { mutableStateOf(current.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("改群名") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(200) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    renaming = false
                    act {
                        api?.rename(roomId, name)
                        repository.refreshRooms()
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("取消") } },
        )
    }

    if (disbanding && current != null) {
        AlertDialog(
            onDismissRequest = { disbanding = false },
            title = { Text("解散「${current.name}」？") },
            text = { Text("群聊记录会删掉，正在进行的讨论会停下。bot 本身和它们各自的对话不受影响。电脑版里这个群也会一起消失。") },
            confirmButton = {
                TextButton(onClick = {
                    disbanding = false
                    scope.launch {
                        try {
                            api?.disband(roomId)
                            repository.refreshRooms()
                            onBack()
                        } catch (e: AdminException) {
                            error = e.message
                        }
                    }
                }) { Text("解散", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { disbanding = false }) { Text("取消") } },
        )
    }
}

/** 在输入框末尾插入 @handle。 */
private fun mention(text: String, handle: String): String {
    val sep = if (text.isEmpty() || text.endsWith(" ") || text.endsWith("\n")) "" else " "
    return "$text$sep@$handle "
}

/** 群里显示哪些记录：消息，以及出错 / 取消 / 到上限这类提示；其它内部事件不显示。 */
private fun shown(event: RoomEvent): Boolean = when (event.kind) {
    "message.user", "message.member", "message" -> event.text.isNotBlank() || event.attachments > 0
    "turn.failed", "turn.cancelled", "turn.deferred", "member.unavailable", "room.stop_requested", "room.disbanded", "authority.lost" -> true
    "room.activity" -> event.status == "bounded"
    else -> false
}

/** 群成员显示的名字：桌面版写进去的显示名 → 这台电脑上 bot 的名字 → @handle。 */
internal fun memberName(profile: String, displayName: String?, handle: String?, bots: List<Bot>): String =
    displayName?.takeIf { it.isNotBlank() }
        ?: bots.firstOrNull { it.name == profile }?.label
        ?: handle?.takeIf { it.isNotBlank() }?.let { "@$it" }
        ?: profile

private fun noticeText(event: RoomEvent, room: Room?, bots: List<Bot>): String {
    val member = room?.members?.firstOrNull { it.memberId == event.memberId }
    val who = event.memberId?.let { memberName(member?.profile ?: it, member?.displayName, member?.handle, bots) } ?: "bot"
    return when (event.kind) {
        "turn.failed" -> "「$who」没回成" + (event.reason?.let { "：$it" } ?: "")
        "turn.cancelled" -> if (event.reason == "superseded_by_newer_user_event") "「$who」的回复被你的新消息取代了" else "「$who」的回复停下了"
        "turn.deferred" -> "「$who」暂时答不了，稍后再试"
        "member.unavailable" -> "「$who」现在用不了"
        "room.stop_requested" -> "已经让大家停下"
        "room.disbanded" -> "群聊已解散"
        "authority.lost" -> "这个群聊换到别的 gateway 上了"
        "room.activity" -> when (event.reason) {
            "max_rounds" -> "讨论了 3 轮，先停在这里；想继续就再发一条"
            "max_messages" -> "这次已经说了 10 条，先停在这里；想继续就再发一条"
            else -> "讨论先停在这里" + (event.reason?.let { "（$it）" } ?: "")
        }
        else -> event.kind
    }
}

@Composable
private fun UserBubble(text: String, maxWidth: androidx.compose.ui.unit.Dp) {
    val colors = LocalHermesColors.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = colors.userBubble,
            contentColor = colors.onUserBubble,
            shape = RoundedCornerShape(20.dp, 6.dp, 20.dp, 20.dp),
            modifier = Modifier.widthIn(max = maxWidth),
        ) {
            MarkdownText(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = colors.onUserBubble)
        }
    }
}

@Composable
private fun MemberBubble(repository: HermesRepository, bot: Bot?, name: String, text: String, maxWidth: androidx.compose.ui.unit.Dp) {
    val colors = LocalHermesColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (bot != null) BotAvatar(repository, bot, 36.dp) else SessionAvatar(name, size = 36.dp)
        Spacer(Modifier.width(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                color = colors.assistantBubble,
                contentColor = colors.onAssistantBubble,
                shape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp),
                modifier = Modifier.widthIn(max = maxWidth),
            ) {
                MarkdownText(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = colors.onAssistantBubble)
            }
        }
    }
}

@Composable
private fun Notice(text: String, isError: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 2.dp),
    )
}

/** 要你处理的事：批准一个操作，或者重试 / 放弃一轮没完成的回复。 */
@Composable
private fun ActionCard(action: RoomAction, name: String, onApprove: (Boolean) -> Unit, onRetry: () -> Unit, onDiscard: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (action.kind) {
                "approval" -> {
                    Text("「$name」要执行一个需要你批准的操作", style = MaterialTheme.typography.titleSmall)
                    action.description?.let {
                        Surface(color = Color.Black.copy(alpha = 0.06f), shape = MaterialTheme.shapes.small) {
                            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp), maxLines = 8, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { onApprove(true) }) { Text("允许一次") }
                        OutlinedButton(onClick = { onApprove(false) }) { Text("拒绝") }
                    }
                }
                "retry" -> {
                    Text("「$name」这一轮没回完", style = MaterialTheme.typography.titleSmall)
                    FilledTonalButton(onClick = onRetry) { Text("重试") }
                }
                else -> {
                    Text("「$name」这一轮的结果不确定（可能已经做了一半）", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDiscard) { Text("放弃这一轮") }
                    }
                }
            }
        }
    }
}

/** 群聊列表里的一行：头像叠在一起、群名、成员。 */
@Composable
internal fun RoomRow(repository: HermesRepository, room: Room, bots: List<Bot>, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp)) {
            val shownBots = room.members.take(2).map { m -> bots.firstOrNull { it.name == m.profile } }
            shownBots.getOrNull(1)?.let { Box(Modifier.align(Alignment.BottomEnd)) { BotAvatar(repository, it, 34.dp) } }
            shownBots.getOrNull(0)?.let { Box(Modifier.align(Alignment.TopStart)) { BotAvatar(repository, it, 34.dp) } }
            if (shownBots.all { it == null }) SessionAvatar(room.name, size = 52.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(room.name.ifBlank { "群聊" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                room.members.joinToString("、") { memberName(it.profile, it.displayName, it.handle, bots) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (room.updatedAt > 0) {
            Text(com.hermes.mobile.ui.components.listTime(room.updatedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
