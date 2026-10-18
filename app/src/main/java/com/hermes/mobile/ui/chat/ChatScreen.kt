package com.hermes.mobile.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.Attachment
import com.hermes.mobile.data.model.Draft
import com.hermes.mobile.data.model.FileRef
import com.hermes.mobile.data.model.ImageRef
import com.hermes.mobile.data.model.MaxImagesPerMessage
import com.hermes.mobile.data.model.imageCount
import com.hermes.mobile.platform.rememberAttachmentPickers
import com.hermes.mobile.platform.rememberFileActions
import com.hermes.mobile.data.model.Role
import com.hermes.mobile.data.model.RunPhase
import com.hermes.mobile.data.model.Session
import com.hermes.mobile.ui.LocalShowMessage
import com.hermes.mobile.ui.chats.ActionSheet
import com.hermes.mobile.ui.chats.RenameDialog
import com.hermes.mobile.ui.chats.SheetAction
import com.hermes.mobile.ui.components.EmptyState
import com.hermes.mobile.ui.components.NoInsets
import com.hermes.mobile.ui.components.SessionAvatar
import com.hermes.mobile.ui.components.dayLabel
import com.hermes.mobile.ui.components.sameDay
import com.hermes.mobile.ui.rememberPlaceholder
import com.hermes.mobile.data.model.Quote
import com.hermes.mobile.data.model.AppFeature
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalTextToolbar
import kotlinx.coroutines.delay
import com.hermes.mobile.ui.glass.GlassCircleButton
import com.hermes.mobile.ui.glass.GlassTopBar
import com.hermes.mobile.ui.glass.TransparentBarColors
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.foundation.background
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch

/** 聊天列表里的一行：日期分隔、一条消息/卡片、正在输入、正在逐字生成。 */
private sealed interface ChatRow {
    val key: String

    data class Date(val label: String) : ChatRow { override val key = "date-$label" }
    data class Item(val item: ChatItem, val first: Boolean, val last: Boolean) : ChatRow { override val key = item.id }
    data object Typing : ChatRow { override val key = "typing" }
    data class Streaming(val text: String) : ChatRow { override val key = "streaming" }
}

private const val GROUP_WINDOW_MS = 3 * 60_000L

private fun buildRows(items: List<ChatItem>, phase: RunPhase, streaming: String?, showTyping: Boolean): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    items.forEachIndexed { index, item ->
        val prev = items.getOrNull(index - 1)
        val next = items.getOrNull(index + 1)
        if (prev == null || !sameDay(prev.time, item.time)) rows += ChatRow.Date(dayLabel(item.time))
        fun sameGroup(a: ChatItem?, b: ChatItem?): Boolean =
            a is ChatItem.Message && b is ChatItem.Message && a.role == b.role &&
                kotlin.math.abs(a.time - b.time) < GROUP_WINDOW_MS && sameDay(a.time, b.time)
        rows += ChatRow.Item(item, first = !sameGroup(prev, item), last = !sameGroup(item, next))
    }
    when {
        streaming != null -> rows += ChatRow.Streaming(streaming)
        phase == RunPhase.Working && showTyping -> rows += ChatRow.Typing
    }
    return rows
}

@Composable
fun ChatScreen(
    repository: HermesRepository,
    sessionId: String,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val session by remember(sessionId) { repository.session(sessionId) }.collectAsState(initial = null)
    val items by remember(sessionId) { repository.chatItems(sessionId) }.collectAsState(initial = emptyList())
    val phase by remember(sessionId) { repository.runPhase(sessionId) }.collectAsState(initial = RunPhase.Idle)
    val streaming by remember(sessionId) { repository.streamingText(sessionId) }.collectAsState(initial = null)
    val settings by repository.settings.collectAsState()
    val models by repository.models.collectAsState()
    val features by repository.features.collectAsState()

    // 草稿存在仓库里：退出聊天再进来、甚至重启 App，输入框里的字还在
    val savedDraft = remember(sessionId) { repository.draft(sessionId) }
    var draft by rememberSaveable(sessionId) { mutableStateOf(savedDraft.text) }
    var attachments by remember(sessionId) { mutableStateOf(savedDraft.attachments) }
    LaunchedEffect(sessionId) {
        snapshotFlow { draft to attachments }.collect { (text, files) -> repository.saveDraft(sessionId, Draft(text, files)) }
    }
    var viewing by remember { mutableStateOf<ImageRef?>(null) }
    var fileTarget by remember { mutableStateOf<FileRef?>(null) }
    var fileBusy by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<ChatItem.Message?>(null) }
    var quote by remember(sessionId) { mutableStateOf<Quote?>(null) }
    var selection by remember { mutableStateOf<MessageSelection?>(null) }
    var highlightId by remember { mutableStateOf<String?>(null) }
    val inputFocus = remember { FocusRequester() }

    val showMessage = LocalShowMessage.current
    val placeholder = rememberPlaceholder()
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val rows = remember(items, phase, streaming, settings.showTyping) {
        buildRows(items, phase, streaming, settings.showTyping).asReversed()
    }

    /** 新选的附件：超过图片上限的丢掉，当前连接发不了的文件提示一下。 */
    fun addAttachments(added: List<Attachment>) {
        var accepted = added
        if (!repository.canSendAnyFile() && accepted.any { it is Attachment.BinaryFile }) {
            accepted = accepted.filterNot { it is Attachment.BinaryFile }
            showMessage("直连和 HTTPS 只能发图片、PDF 和文本类文件；其它文件需要 SSH 或本机 Termux 连接")
        }
        val room = MaxImagesPerMessage - attachments.imageCount()
        if (accepted.imageCount() > room) {
            showMessage("一条消息最多 $MaxImagesPerMessage 张图片（PDF 每页算一张）")
            var left = room
            accepted = accepted.filter { a ->
                val n = listOf(a).imageCount()
                (n <= left).also { if (it) left -= n }
            }
        }
        attachments = attachments + accepted
    }
    val pickers = rememberAttachmentPickers(onAdd = ::addAttachments, onError = showMessage)

    fun send() {
        repository.sendMessage(sessionId, draft.trim(), attachments, quote)
        draft = ""
        attachments = emptyList()
        quote = null
        scope.launch { listState.animateScrollToItem(0) }
    }

    fun startQuote(q: Quote) {
        quote = q
        selection?.clear()
        selection = null
        runCatching { inputFocus.requestFocus() }
    }

    /** 点引用块：滚回被引用的那条消息并闪一下。 */
    fun jumpTo(messageId: String) {
        val index = rows.indexOfFirst { it.key == messageId }
        if (index < 0) {
            showMessage("原消息已不在这个会话里")
            return
        }
        scope.launch {
            listState.animateScrollToItem(index)
            highlightId = messageId
            delay(700)
            highlightId = null
        }
    }

    val backdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    var bottomBarHeight by remember { mutableIntStateOf(0) }
    val bottomBarSpace = with(density) { bottomBarHeight.toDp() }

    CompositionLocalProvider(LocalOpenImage provides { viewing = it }, LocalOpenFile provides { fileTarget = it }) {
    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            GlassTopBar(backdrop) {
                ChatTopBar(
                    session = session,
                    phase = phase,
                    onBack = onBack,
                    onModelClick = { if (session?.readOnly == false) showModels = true },
                    onRename = { showRename = true },
                    onFork = { repository.forkSession(sessionId, null)?.let(onOpenChat) },
                    onSearch = { placeholder("会话内搜索") },
                    onDiagnostics = { placeholder("查看原始事件（诊断模式）") },
                    onDelete = {
                        repository.deleteSession(sessionId)
                        onBack()
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            // 消息列表铺满整屏，滚到顶栏和悬浮输入框下面，两者从这里取样做玻璃
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .background(MaterialTheme.colorScheme.background),
            ) {
                val bubbleWidth = maxWidth
                if (items.isEmpty() && phase == RunPhase.Idle) {
                    NewChatHint(Modifier.align(Alignment.Center))
                }
                MessageList(
                    rows = rows,
                    listState = listState,
                    maxWidth = bubbleWidth,
                    contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = bottomBarSpace + 12.dp),
                    highlightId = highlightId,
                    onSelectionChange = { id, sel ->
                        val current = selection
                        when {
                            sel != null -> {
                                // 新的选择出现时，清掉另一条消息里的旧选择
                                if (current != null && current.message.id != id) current.clear()
                                selection = sel
                            }
                            current?.message?.id == id -> selection = null
                        }
                    },
                    onQuote = ::startQuote,
                    onQuoteClick = ::jumpTo,
                    onMore = { actionTarget = it },
                    onRetry = { repository.retryMessage(sessionId, it.id) },
                    onCopyCode = { showMessage("代码已复制") },
                    onApprove = { item, choice -> repository.resolveApproval(sessionId, item.id, choice) },
                    onAnswer = { item, answer -> repository.answerClarify(sessionId, item.id, answer) },
                )
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { bottomBarHeight = it.height },
            ) {
                val current = session
                if (current != null && current.readOnly) {
                    ReadOnlyBar(backdrop, current.source.label, onFork = { repository.forkSession(sessionId, null)?.let(onOpenChat) })
                } else if (AppFeature.SendMessages !in features) {
                    NoticeBar(backdrop, "当前版本还不能发消息，下一阶段接入")
                } else {
                    ChatInputBar(
                        backdrop = backdrop,
                        text = draft,
                        onTextChange = { draft = it },
                        attachments = attachments,
                        onRemoveAttachment = { image -> attachments = attachments - image },
                        onAttach = { showAttach = true },
                        // Hermes 回复时：输入框空着是“停止”，打了字就是“插话”（它在下一步看到，不打断）
                        mode = if (phase != RunPhase.Idle && draft.isBlank() && attachments.isEmpty()) SendButtonMode.Stop else SendButtonMode.Send,
                        placeholder = when {
                            quote != null -> "针对这段追问…"
                            phase != RunPhase.Idle -> "补充一句，Hermes 会在下一步看到"
                            else -> "给 Hermes 发消息"
                        },
                        onSend = ::send,
                        onStop = { repository.stop(sessionId) },
                        enterToSend = settings.enterToSend,
                        quote = quote,
                        onClearQuote = { quote = null },
                        focusRequester = inputFocus,
                    )
                }
            }
            SelectionBar(
                backdrop = backdrop,
                selection = selection,
                onAsk = { sel -> Quote.of(sel.message, sel.text)?.let(::startQuote) },
                onCopy = { sel ->
                    clipboard.setText(AnnotatedString(sel.text))
                    sel.clear()
                    showMessage("已复制")
                },
                onMore = { sel ->
                    sel.clear()
                    actionTarget = sel.message
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = with(density) { bottomBarHeight.toDp() } + 4.dp),
            )
            JumpToBottom(
                backdrop,
                listState,
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = bottomBarSpace + 8.dp),
            ) {
                scope.launch { listState.animateScrollToItem(0) }
            }
        }
    }
    }

    if (showModels) {
        ModelPickerSheet(
            models = models,
            selectedName = session?.model,
            onSelect = {
                repository.setSessionModel(sessionId, it.id)
                showModels = false
            },
            onDismiss = { showModels = false },
        )
    }

    if (showAttach) {
        AttachSheet(
            onPickPhotos = {
                showAttach = false
                pickers.pickPhotos()
            },
            onCamera = {
                showAttach = false
                pickers.takePhoto()
            },
            onPickFiles = {
                showAttach = false
                pickers.pickFiles()
            },
            anyFile = repository.canSendAnyFile(),
            onDismiss = { showAttach = false },
        )
    }

    val fileActions = rememberFileActions(onError = showMessage)
    fileTarget?.let { file ->
        /** 先把文件准备到手机上（服务器上的经 SSH 下载），再交给系统。 */
        fun withLocal(action: (String) -> Unit) {
            fileBusy = true
            scope.launch {
                val path = repository.fileForOpening(file)
                fileBusy = false
                if (path == null) {
                    showMessage("这个文件在 Hermes 那台机器上，给连接配上 SSH（本机 Termux 打开“文件互通”）后才能打开")
                } else {
                    fileTarget = null
                    action(path)
                }
            }
        }
        FileActionSheet(
            file = file,
            busy = fileBusy,
            onOpen = { withLocal { fileActions.open(it, file.name) } },
            onShare = { withLocal { fileActions.share(it, file.name) } },
            onSave = { withLocal { path -> showMessage(fileActions.save(path, file.name) ?: "保存失败") } },
            onDismiss = { if (!fileBusy) fileTarget = null },
        )
    }

    viewing?.let { image ->
        ImageViewer(
            image,
            onDismiss = { viewing = null },
            onSave = { source -> showMessage(fileActions.saveImage(source, imageFileName(image)) ?: "保存失败") },
            onShare = { source ->
                if (source.startsWith("data:")) showMessage("这张图暂时不能分享，先保存到相册再分享")
                else fileActions.share(source, imageFileName(image))
            },
        )
    }

    if (showRename) {
        RenameDialog(
            initial = session?.title.orEmpty(),
            onDismiss = { showRename = false },
            onConfirm = {
                repository.renameSession(sessionId, it)
                showRename = false
            },
        )
    }

    actionTarget?.let { message ->
        val readOnly = session?.readOnly == true
        ActionSheet(
            title = null,
            onDismiss = { actionTarget = null },
            actions = buildList {
                add(SheetAction("复制", Icons.Rounded.ContentCopy, {
                    clipboard.setText(AnnotatedString(message.text))
                    showMessage("已复制")
                }))
                if (message.role == Role.Assistant && !readOnly) {
                    add(SheetAction("重新生成", Icons.Rounded.Refresh, { repository.regenerate(sessionId, message.id) }))
                }
                add(SheetAction("从这里分叉", Icons.Rounded.AccountTree, { repository.forkSession(sessionId, message.id)?.let(onOpenChat) }))
                if (!readOnly) {
                    add(SheetAction("删除", Icons.Rounded.Delete, { repository.deleteItem(sessionId, message.id) }, destructive = true))
                }
            },
        )
    }
}

@Composable
private fun MessageList(
    rows: List<ChatRow>,
    listState: LazyListState,
    maxWidth: Dp,
    contentPadding: PaddingValues,
    highlightId: String?,
    onSelectionChange: (String, MessageSelection?) -> Unit,
    onQuote: (Quote) -> Unit,
    onQuoteClick: (String) -> Unit,
    onMore: (ChatItem.Message) -> Unit,
    onRetry: (ChatItem.Message) -> Unit,
    onCopyCode: (String) -> Unit,
    onApprove: (ChatItem.Approval, String) -> Unit,
    onAnswer: (ChatItem.Clarify, String) -> Unit,
) {
    // 选中文字时用我们自己的玻璃操作栏，屏蔽系统的浮动工具条，避免两套菜单同时出现
    CompositionLocalProvider(LocalTextToolbar provides NoTextToolbar) {
        // reverseLayout：最新的消息在底部，新消息进来时自然贴底
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(rows, key = { it.key }, contentType = { it::class }) { row ->
                Box(Modifier.animateItem()) {
                    when (row) {
                        is ChatRow.Date -> DateSeparator(row.label)
                        is ChatRow.Typing -> TypingRow()
                        is ChatRow.Streaming -> StreamingRow(row.text, maxWidth)
                        is ChatRow.Item -> when (val item = row.item) {
                            is ChatItem.Message -> MessageBubble(
                                message = item,
                                first = row.first,
                                last = row.last,
                                maxWidth = maxWidth,
                                onRetry = { onRetry(item) },
                                onCopyCode = onCopyCode,
                                highlighted = item.id == highlightId,
                                onSelectionChange = onSelectionChange,
                                onQuote = onQuote,
                                onQuoteClick = onQuoteClick,
                                onMore = { onMore(item) },
                            )
                            is ChatItem.Approval -> ApprovalCard(item) { onApprove(item, it) }
                            is ChatItem.Clarify -> ClarifyCard(item) { onAnswer(item, it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JumpToBottom(backdrop: Backdrop, listState: LazyListState, modifier: Modifier, onClick: () -> Unit) {
    val show by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }
    AnimatedVisibility(show, modifier = modifier, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
        GlassCircleButton(
            backdrop = backdrop,
            icon = Icons.Rounded.KeyboardArrowDown,
            contentDescription = "回到最新",
            onClick = onClick,
            size = 44.dp,
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun NewChatHint(modifier: Modifier = Modifier) {
    EmptyState(
        Icons.Rounded.AutoAwesome,
        "开始新对话",
        "像发消息一样告诉 Hermes 你要做什么。\n它只会把结果发给你，需要你确认时会弹出卡片。",
        modifier = modifier,
    )
}

@Composable
private fun ChatTopBar(
    session: Session?,
    phase: RunPhase,
    onBack: () -> Unit,
    onModelClick: () -> Unit,
    onRename: () -> Unit,
    onFork: () -> Unit,
    onSearch: () -> Unit,
    onDiagnostics: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column {
        TopAppBar(
            colors = TransparentBarColors(),
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回") }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SessionAvatar(session?.title ?: "", size = 38.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.clickable(onClick = onModelClick)) {
                        Text(
                            session?.title ?: "",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        AnimatedContent(
                            targetState = phase,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "subtitle",
                        ) { current ->
                            when {
                                current == RunPhase.Working -> Subtitle("正在输入…", MaterialTheme.colorScheme.primary)
                                current == RunPhase.AwaitingInput -> Subtitle("等待你的确认", MaterialTheme.colorScheme.tertiary)
                                session?.readOnly == true -> Subtitle("${session.source.label} 会话 · 只读", MaterialTheme.colorScheme.onSurfaceVariant)
                                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    Subtitle(session?.model ?: "默认模型", MaterialTheme.colorScheme.onSurfaceVariant)
                                    Icon(
                                        Icons.Rounded.ExpandMore,
                                        contentDescription = "切换模型",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            },
            actions = {
                IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, contentDescription = "搜索") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "更多") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val readOnly = session?.readOnly == true
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            enabled = !readOnly,
                            onClick = { menu = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text("分叉会话") },
                            leadingIcon = { Icon(Icons.Rounded.AccountTree, null) },
                            onClick = { menu = false; onFork() },
                        )
                        DropdownMenuItem(
                            text = { Text("查看原始事件") },
                            leadingIcon = { Icon(Icons.Rounded.BugReport, null) },
                            onClick = { menu = false; onDiagnostics() },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("删除会话", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun Subtitle(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
}
