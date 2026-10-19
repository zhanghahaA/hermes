package com.hermes.mobile.ui.bots

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.model.Bot
import com.hermes.mobile.data.model.BotEdit
import com.hermes.mobile.data.model.BotSaved
import com.hermes.mobile.data.model.botIdFor
import com.hermes.mobile.platform.rememberAvatarPicker
import com.hermes.mobile.platform.rememberImageBitmap
import com.hermes.mobile.ui.chat.ModelPickerSheet
import kotlinx.coroutines.launch

/** 可选的 bot 颜色（没有头像照片时，头像是这个颜色的圆）。 */
internal val BotColors = listOf(
    "#5C6BC0", "#4F8CFF", "#00897B", "#43A047", "#F4B400",
    "#F4511E", "#E5484D", "#D81B60", "#8E24AA", "#546E7A",
)

/**
 * 新建（[botName] 为 null）或编辑一个 bot：头像、名字、它负责什么、性格（SOUL.md）、模型。
 * 都是经 SSH 调 Hermes 自己的 Profile 接口改的，和电脑版 Bot 里改是一回事。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BotEditorScreen(repository: HermesRepository, botName: String?, onClose: () -> Unit) {
    val roster by repository.bots.collectAsState()
    val existing = remember(botName) { roster?.bots?.firstOrNull { it.name == botName } }
    val creating = botName == null
    val hasSsh = remember { repository.admin() != null }
    val models by repository.models.collectAsState()
    val scope = rememberCoroutineScope()

    var title by rememberSaveable { mutableStateOf(existing?.label.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(existing?.description.orEmpty()) }
    var soul by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf(existing?.color ?: BotColors.first()) }
    var newAvatar by rememberSaveable { mutableStateOf<String?>(null) }
    var avatarCleared by rememberSaveable { mutableStateOf(false) }
    var provider by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }

    // 编辑：先从服务器读出现在的性格和模型（读到之前不让存，免得把空的写回去）
    var loaded by remember { mutableStateOf(creating) }
    var original by remember { mutableStateOf<com.hermes.mobile.data.model.BotDetails?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(botName) {
        if (botName == null || loaded) return@LaunchedEffect
        repository.botDetails(botName)
            .onSuccess { d ->
                original = d
                description = d.description.ifBlank { description }
                soul = d.soul
                provider = d.provider
                model = d.model
                loaded = true
            }
            .onFailure { loadError = it.message ?: "读不到这个 bot 的设置" }
    }

    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmModel by remember { mutableStateOf<String?>(null) }
    var created by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var pickModel by remember { mutableStateOf(false) }

    val pickAvatar = rememberAvatarPicker(
        onImage = { newAvatar = it; avatarCleared = false },
        onError = { error = it },
    )

    val id = if (creating) botIdFor(title) else botName.orEmpty()
    val idProblem = when {
        !creating -> null
        title.isBlank() -> null
        id.isEmpty() -> "这个名字转不出能用的 ID，换一个试试"
        id == "default" -> "default 是默认 bot 的名字，换一个"
        roster?.bots?.any { it.name == id } == true -> "已经有叫这个的 bot 了"
        else -> null
    }
    val canSave = hasSsh && loaded && !saving && title.isNotBlank() && idProblem == null

    fun save(confirmExpensive: Boolean = false) {
        saving = true
        error = null
        val edit = if (creating) {
            BotEdit(
                name = id,
                title = title.trim(),
                description = description.trim(),
                soul = soul.trim().ifEmpty { null },
                color = color,
                avatar = newAvatar,
            )
        } else {
            val o = original
            val modelChanged = model.isNotBlank() && (model != o?.model || provider != o?.provider)
            BotEdit(
                name = id,
                title = title.trim().takeIf { it != existing?.label },
                description = description.trim().takeIf { it != o?.description?.trim() },
                soul = soul.takeIf { it.trim() != o?.soul?.trim() },
                color = color.takeIf { it != existing?.color },
                avatar = newAvatar,
                clearAvatar = avatarCleared && newAvatar == null,
                provider = provider.takeIf { modelChanged },
                model = model.takeIf { modelChanged },
                confirmExpensiveModel = confirmExpensive,
            )
        }
        scope.launch {
            repository.saveBot(edit, create = creating)
                .onSuccess { saved ->
                    when (saved) {
                        is BotSaved.ConfirmModel -> confirmModel = saved.message
                        is BotSaved.Done -> if (creating) created = title.trim() to saved.needsModel else onClose()
                    }
                }
                .onFailure { error = it.message ?: "没保存成功" }
            saving = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "关闭") } },
                title = { Text(if (creating) "新建 bot" else "编辑「${existing?.label ?: botName}」") },
                actions = {
                    if (saving) {
                        CircularProgressIndicator(Modifier.padding(end = 20.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Button(onClick = { save() }, enabled = canSave, modifier = Modifier.padding(end = 8.dp)) {
                            Text(if (creating) "创建" else "保存")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (!hasSsh) Banner("新建和编辑 bot 要经 SSH 改那台电脑上的 Hermes，给这个连接配上 SSH 后再来。", isError = true)
            loadError?.let { Banner(it, isError = true) }
            error?.let { Banner(it, isError = true) }

            // 头像 + 颜色
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val preview = (existing ?: blankBot(id)).copy(
                    title = title,
                    color = color,
                    avatarPath = existing?.avatarPath?.takeIf { !avatarCleared },
                )
                val picked = rememberImageBitmap(newAvatar, maxPx = 256)
                Box(Modifier.size(96.dp).clip(CircleShape).clickable(enabled = hasSsh, onClick = pickAvatar)) {
                    if (picked != null) {
                        Image(picked, contentDescription = "头像", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        BotAvatar(repository, preview, 96.dp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = pickAvatar, enabled = hasSsh) {
                        Icon(Icons.Rounded.Photo, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("选照片")
                    }
                    if (newAvatar != null || (existing?.avatarPath != null && !avatarCleared)) {
                        TextButton(onClick = { newAvatar = null; avatarCleared = existing?.avatarPath != null }) { Text("不用照片") }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BotColors.forEach { c ->
                        val selected = c.equals(color, ignoreCase = true)
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(parseBotColor(c) ?: Color.Gray)
                                .then(if (selected) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface), CircleShape) else Modifier)
                                .clickable { color = c },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) Icon(Icons.Rounded.Check, contentDescription = "已选", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("名字") },
                placeholder = { Text("例如：小助手、码农") },
                singleLine = true,
                isError = idProblem != null,
                supportingText = {
                    Text(
                        idProblem ?: when {
                            !creating -> "ID：$id（ID 改不了）"
                            id.isNotEmpty() -> "ID：$id（Hermes 里的 Profile 名，用来区分 bot）"
                            else -> "Hermes 里会用它生成一个英文 ID"
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("它负责什么") },
                placeholder = { Text("例如：帮我写脚本、看日志、改配置") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = soul,
                onValueChange = { soul = it },
                enabled = loaded,
                label = { Text("性格（SOUL.md）") },
                placeholder = {
                    Text(if (creating) "可以不填：按名字和「它负责什么」自动写一份。也可以写它说话的风格、要遵守的规矩……" else if (!loaded) "正在读…" else "")
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                minLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )

            if (creating) {
                Text(
                    "新 bot 会从默认 bot 复制模型、API Key 和技能（不复制 Telegram 等聊天平台的 bot token），之后可以单独改。" +
                        "建好后在 Bot 列表里点它，按提示开通手机访问就能聊了。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = loaded && models.isNotEmpty()) { pickModel = true },
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("模型", style = MaterialTheme.typography.titleSmall)
                            Text(
                                when {
                                    !loaded -> "正在读…"
                                    model.isBlank() -> "没设（跟随默认）"
                                    else -> listOf(provider, model).filter { it.isNotBlank() }.joinToString(" · ")
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.Rounded.ChevronRight, null)
                    }
                }
            }
        }
    }

    if (pickModel) {
        ModelPickerSheet(
            models = models,
            selectedName = model,
            onSelect = { option ->
                provider = option.providerSlug
                model = option.model
                pickModel = false
            },
            onDismiss = { pickModel = false },
        )
    }

    confirmModel?.let { message ->
        AlertDialog(
            onDismissRequest = { confirmModel = null },
            title = { Text("确定用这个模型？") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { confirmModel = null; save(confirmExpensive = true) }) { Text("仍然使用") } },
            dismissButton = { TextButton(onClick = { confirmModel = null }) { Text("再想想") } },
        )
    }

    created?.let { (name, needsModel) ->
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("「$name」建好了") },
            text = {
                Text(
                    needsModel?.let { "不过它现在还没有能用的模型：$it\n\n在列表里长按它 → 编辑，给它选一个模型。" }
                        ?: "在 Bot 列表里点它就能开始聊（第一次要开通手机访问）。",
                )
            },
            confirmButton = { TextButton(onClick = onClose) { Text("好") } },
        )
    }
}

@Composable
private fun Banner(text: String, isError: Boolean) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

private fun blankBot(name: String) = Bot(name, false, "", "", "", null, false, null, null, null, null)
