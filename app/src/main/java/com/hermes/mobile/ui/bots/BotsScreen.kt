package com.hermes.mobile.ui.bots

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import com.hermes.mobile.data.model.RoomMember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.model.Bot
import com.hermes.mobile.data.model.BotSwitch
import com.hermes.mobile.platform.rememberImageBitmap
import com.hermes.mobile.ui.components.EmptyState
import com.hermes.mobile.ui.components.NoInsets
import com.hermes.mobile.ui.components.SessionAvatar
import com.hermes.mobile.ui.components.TagLabel
import com.hermes.mobile.ui.components.listTime
import com.hermes.mobile.ui.glass.GlassTopBar
import com.hermes.mobile.ui.glass.TransparentBarColors
import com.hermes.mobile.ui.main.LocalBottomBarSpace
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch

/**
 * Bot（Hermes 的 Profile，桌面版叫 Bot）：每个 bot 有自己的性格、模型、对话和任务。
 * 点一个 bot 就换成和它聊，打开它的固定对话「Bot Chat」。
 */
@Composable
fun BotsScreen(
    repository: HermesRepository,
    /** 打开某个对话（换好 bot 之后）。 */
    onOpenChat: (String) -> Unit,
    /** 去聊天列表看这个 bot 的所有对话。 */
    onShowChats: () -> Unit,
    /** 编辑 bot（null 是新建）。 */
    onEditBot: (String?) -> Unit = {},
    /** 打开一个群聊。 */
    onOpenRoom: (String) -> Unit = {},
) {
    val roster by repository.bots.collectAsState()
    val active by repository.activeBot.collectAsState()
    val error by repository.botsError.collectAsState()
    val hasSsh = remember(repository.connectionState.collectAsState().value) { repository.admin() != null }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var enableFor by remember { mutableStateOf<Bot?>(null) }
    var deleteFor by remember { mutableStateOf<Bot?>(null) }
    val rooms by repository.rooms.collectAsState()
    val roomsError by repository.roomsError.collectAsState()
    var newRoom by remember { mutableStateOf(false) }
    val backdrop = rememberLayerBackdrop()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val bottomSpace = LocalBottomBarSpace.current

    LaunchedEffect(Unit) {
        launch { repository.refreshBots() }
        launch { repository.refreshRooms() }
    }

    /** 换到 [bot]，然后 [then]（打开对话或去聊天列表）。 */
    fun open(bot: Bot, then: () -> Unit) {
        if (bot.name == active) {
            then()
            return
        }
        busy = "正在切换到「${bot.label}」…"
        scope.launch {
            when (val r = repository.switchBot(bot.name)) {
                BotSwitch.Done -> then()
                BotSwitch.NeedsAccess -> enableFor = bot
                is BotSwitch.Failed -> message = "没能切换到「${bot.label}」：${r.reason}"
            }
            busy = null
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = NoInsets,
        containerColor = Color.Transparent,
        topBar = {
            GlassTopBar(backdrop) {
                LargeTopAppBar(
                    title = { Text("Bot") },
                    actions = {
                        IconButton(
                            enabled = !refreshing && hasSsh,
                            onClick = {
                                scope.launch {
                                    refreshing = true
                                    launch { repository.refreshRooms() }
                                    repository.refreshBots()
                                    refreshing = false
                                }
                            },
                        ) { Icon(Icons.Rounded.Refresh, contentDescription = "刷新") }
                    },
                    colors = TransparentBarColors(),
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(MaterialTheme.colorScheme.background),
        ) {
            val bots = roster?.bots.orEmpty()
            LazyColumn(
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 4.dp, bottom = bottomSpace + 16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    !hasSsh && bots.isEmpty() -> item(key = "ssh") {
                        EmptyState(
                            Icons.Rounded.Terminal,
                            "需要 SSH",
                            "Bot 就是 Hermes 的 Profile。看有哪些 bot、和它们聊，要经 SSH 读那台电脑上的 Hermes，给这个连接配上 SSH 后再来。",
                        )
                    }
                    bots.isEmpty() && error != null -> item(key = "error") {
                        EmptyState(Icons.Rounded.SmartToy, "没读到 bot", error.orEmpty())
                    }
                    bots.isEmpty() -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
                if (bots.size > 1 && roster?.multiplex != true) {
                    item(key = "multiplex") {
                        Notice(
                            "电脑上的 Hermes gateway 现在只服务一个 bot。点别的 bot 时，App 会帮你开通（打开 multiplex_profiles，需要重启一次 gateway）。",
                        )
                    }
                }
                // 群聊：几个 bot 在一个房间里讨论（电脑版的群聊，同一份）
                val roomList = rooms.orEmpty()
                if (hasSsh && (bots.size >= 2 || roomList.isNotEmpty())) {
                    item(key = "rooms-header") {
                        SectionTitle("群聊") {
                            TextButton(onClick = { newRoom = true }, enabled = bots.size >= 2) { Text("新建群聊") }
                        }
                    }
                    items(roomList, key = { "room-" + it.id }) { room ->
                        RoomRow(repository, room, bots, onOpen = { onOpenRoom(room.id) })
                    }
                    if (roomList.isEmpty()) {
                        item(key = "rooms-empty") {
                            Text(
                                roomsError ?: if (rooms == null) "正在读群聊…" else "还没有群聊。拉几个 bot 进群，它们会一起讨论你的问题。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (roomsError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    if (bots.isNotEmpty()) item(key = "bots-header") { SectionTitle("Bot") }
                }
                items(bots, key = { it.name }) { bot ->
                    BotRow(
                        repository = repository,
                        bot = bot,
                        active = bot.name == active,
                        onOpen = { open(bot) { repository.openBotChat()?.let(onOpenChat) } },
                        onShowChats = { open(bot) { onShowChats() } },
                        onEnable = { enableFor = bot },
                        onEdit = { onEditBot(bot.name) }.takeIf { hasSsh },
                        onDelete = { deleteFor = bot }.takeIf { hasSsh && !bot.isDefault && bot.name != "default" },
                    )
                }
            }
            busy?.let { text ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(text)
                    }
                }
            }
        }
    }

    enableFor?.let { bot ->
        EnableAccessDialog(
            bot = bot,
            multiplexOff = roster?.multiplex != true,
            onDismiss = { enableFor = null },
            onEnable = { progress -> repository.enableBotAccess(bot.name, progress) },
            onDone = {
                enableFor = null
                repository.openBotChat()?.let(onOpenChat)
            },
        )
    }

    if (newRoom) {
        NewRoomDialog(
            repository = repository,
            bots = roster?.bots.orEmpty(),
            multiplexOff = roster?.multiplex != true,
            onDismiss = { newRoom = false },
            onCreated = { id ->
                newRoom = false
                onOpenRoom(id)
            },
        )
    }

    deleteFor?.let { bot ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("删除「${bot.label}」？") },
            text = {
                Text("会从电脑上删掉这个 bot（Hermes Profile「${bot.name}」）：它的对话、记忆、技能、定时任务都没了，不能恢复。电脑版里也会一起消失。")
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteFor = null
                    busy = "正在删除「${bot.label}」…"
                    scope.launch {
                        repository.deleteBot(bot.name)?.let { message = "没删掉「${bot.label}」：$it" }
                        busy = null
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("取消") } },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("没成功") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** 新建群聊：起个名字，勾 2～6 个 bot。 */
@Composable
private fun NewRoomDialog(
    repository: HermesRepository,
    bots: List<Bot>,
    multiplexOff: Boolean,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val chosen = bots.filter { it.name in picked }
    val autoName = chosen.joinToString("、") { it.label }.take(60)
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("新建群聊") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(200) },
                    label = { Text("群名") },
                    placeholder = { Text(autoName.ifBlank { "例如：周末出游" }) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("拉谁进群（2～6 个）", style = MaterialTheme.typography.labelLarge)
                Column(
                    Modifier
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    bots.forEach { bot ->
                        val on = bot.name in picked
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = on || picked.size < 6) { picked = if (on) picked - bot.name else picked + bot.name }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            BotAvatar(repository, bot, 32.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(bot.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("@" + roomHandle(bot), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (multiplexOff) {
                    Text(
                        "群里的 bot 都由电脑上的 gateway 同时服务，要先打开 multiplex_profiles：在列表里点任意一个别的 bot，按提示开通手机访问时会一起打开。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "在群里发一条消息，bot 们会依次回答；@ 某个 bot 就只让它回答，它们之间互相 @ 会再接着聊（最多 3 轮）。电脑版里也能看到这个群。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !running && picked.size in 2..6,
                onClick = {
                    val api = repository.roomApi() ?: run { error = "群聊要经 SSH，给这个连接配上 SSH 后再试"; return@TextButton }
                    running = true
                    error = null
                    scope.launch {
                        try {
                            val members = chosen.map { RoomMember(it.name, it.name, roomHandle(it), it.label) }
                            val room = api.create(name.ifBlank { autoName }, members)
                            repository.refreshRooms()
                            onCreated(room.id)
                        } catch (e: com.hermes.mobile.data.remote.AdminException) {
                            error = e.message
                        }
                        running = false
                    }
                },
            ) { Text(if (running) "正在建…" else "建群") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !running) { Text("取消") } },
    )
}

/** 群里 @ 这个 bot 用的名字，和电脑版一样：默认 bot 叫 hermes，其它就是 Profile 名。 */
internal fun roomHandle(bot: Bot): String = if (bot.isDefault || bot.name == "default") "hermes" else bot.name

@Composable
private fun Notice(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

@Composable
private fun BotRow(
    repository: HermesRepository,
    bot: Bot,
    active: Boolean,
    onOpen: () -> Unit,
    onShowChats: () -> Unit,
    onEnable: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    val needsAccess = !bot.isDefault && !bot.hasKey
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotAvatar(repository, bot, 52.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(bot.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (active) {
                    Spacer(Modifier.width(6.dp))
                    TagLabel("正在用", container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary)
                }
            }
            val sub = bot.last?.preview?.takeIf { it.isNotBlank() } ?: bot.description.ifBlank { if (needsAccess) "还没开通手机访问" else "还没有对话" }
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOfNotNull(
                    bot.model,
                    bot.name.takeIf { it != bot.label },
                    "未开通手机访问".takeIf { needsAccess },
                ).forEachIndexed { i, text ->
                    if (i > 0) Text(" · ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            bot.last?.lastActive?.takeIf { it > 0 }?.let {
                Text(listTime(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "更多") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("和它聊（Bot Chat）") },
                        leadingIcon = { Icon(Icons.Rounded.SmartToy, null) },
                        onClick = { menu = false; onOpen() },
                    )
                    DropdownMenuItem(
                        text = { Text("看它的所有对话") },
                        leadingIcon = { Icon(Icons.Rounded.Forum, null) },
                        onClick = { menu = false; onShowChats() },
                    )
                    if (needsAccess) {
                        DropdownMenuItem(
                            text = { Text("开通手机访问") },
                            leadingIcon = { Icon(Icons.Rounded.Key, null) },
                            onClick = { menu = false; onEnable() },
                        )
                    }
                    onEdit?.let { edit ->
                        DropdownMenuItem(
                            text = { Text("编辑") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = { menu = false; edit() },
                        )
                    }
                    onDelete?.let { delete ->
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; delete() },
                        )
                    }
                }
            }
        }
    }
}

/** bot 的头像：有头像文件就显示图片，否则用名字首字（带桌面版选的颜色）。 */
@Composable
fun BotAvatar(repository: HermesRepository, bot: Bot, size: Dp) {
    val dataUrl by produceState<String?>(null, bot.avatarPath, bot.avatarVersion) {
        value = repository.botAvatar(bot)?.let { "data:image/*;base64," + java.util.Base64.getEncoder().encodeToString(it) }
    }
    val image = rememberImageBitmap(dataUrl, maxPx = 256)
    if (image != null) {
        Image(image, contentDescription = bot.label, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
    } else {
        val color = bot.color?.let(::parseBotColor)
        if (color != null) {
            Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Text(bot.label.take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
        } else {
            SessionAvatar(bot.label, size = size)
        }
    }
}

/** "#RRGGBB" / "#AARRGGBB" → 颜色；格式不对返回 null。 */
internal fun parseBotColor(text: String): Color? {
    val hex = text.trim().removePrefix("#")
    val argb = when (hex.length) {
        6 -> hex.toLongOrNull(16)?.let { 0xFF000000 or it }
        8 -> hex.toLongOrNull(16)
        else -> null
    } ?: return null
    return Color(argb)
}

/** 开通 bot 的手机访问：先说清楚要改什么，点了才做，显示进度。 */
@Composable
private fun EnableAccessDialog(
    bot: Bot,
    multiplexOff: Boolean,
    onDismiss: () -> Unit,
    onEnable: suspend ((String) -> Unit) -> String?,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("开通「${bot.label}」的手机访问") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Hermes 要求每个 bot 用自己的 API Key，手机才能直接和它聊。App 会经 SSH 在电脑上：", style = MaterialTheme.typography.bodyMedium)
                Text("• 给「${bot.label}」生成一个 API Key，写进它自己的 .env（已有就用原来的）", style = MaterialTheme.typography.bodyMedium)
                if (multiplexOff) {
                    Text("• 打开 gateway.multiplex_profiles，让一个 gateway 同时服务所有 bot", style = MaterialTheme.typography.bodyMedium)
                    Text("• 重启一次 gateway：正在进行的回复会中断，电脑版也会短暂断开", style = MaterialTheme.typography.bodyMedium)
                }
                step?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !running,
                onClick = {
                    running = true
                    error = null
                    scope.launch {
                        val failure = onEnable { step = it }
                        running = false
                        if (failure == null) onDone() else {
                            step = null
                            error = failure
                        }
                    }
                },
            ) { Text(if (error != null) "重试" else "开通") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !running) { Text("取消") } },
    )
}
