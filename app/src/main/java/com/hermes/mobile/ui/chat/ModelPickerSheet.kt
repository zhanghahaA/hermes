package com.hermes.mobile.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.hermes.mobile.data.model.ReasoningEfforts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.model.ModelOption
import com.hermes.mobile.ui.components.IconBadge
import com.hermes.mobile.ui.components.TagLabel

/**
 * 切换模型的底部面板。[onEffort] 不为空时上面多一排“推理强度”（只对这个对话生效，随每一轮请求带给 Hermes）；
 * 当前模型 Hermes 说不支持推理时不显示。
 */
@Composable
fun ModelPickerSheet(
    models: List<ModelOption>,
    selectedName: String?,
    onSelect: (ModelOption) -> Unit,
    onDismiss: () -> Unit,
    effort: String? = null,
    onEffort: ((String?) -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ModelSheetBody(models, selectedName, onSelect, effort, onEffort, Modifier.navigationBarsPadding())
    }
}

/** [ModelPickerSheet] 里面的内容（预览截图也用它）。 */
@Composable
fun ModelSheetBody(
    models: List<ModelOption>,
    selectedName: String?,
    onSelect: (ModelOption) -> Unit,
    effort: String? = null,
    onEffort: ((String?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isSelected: (ModelOption) -> Boolean = { it.model == selectedName || it.name == selectedName }
    val current = models.firstOrNull(isSelected)
    ModelPickerContent(
        models = models,
        isSelected = isSelected,
        onSelect = onSelect,
        header = {
            item(key = "title") {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                    Text("切换模型", style = MaterialTheme.typography.titleLarge)
                    Text("只对这个对话生效", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onEffort != null && current?.reasoning != false) {
                item(key = "effort") { EffortRow(effort, onEffort) }
            }
        },
        modifier = modifier,
    )
}

/** 推理强度：默认 / 关闭 / 低 / 中 / 高 / 极高，横向一排可以滑。 */
@Composable
private fun EffortRow(effort: String?, onEffort: (String?) -> Unit) {
    Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
        Text("推理强度", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 24.dp))
        Text(
            "越高想得越久、越费 token；「默认」用 Hermes 配置里的设置",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReasoningEfforts.forEach { (value, label) ->
                FilterChip(
                    selected = effort == value,
                    onClick = { onEffort(value) },
                    label = { Text(label) },
                    modifier = Modifier.testTag("effort-${value ?: "default"}"),
                )
            }
        }
    }
}

/**
 * 按 Provider 分组、可以搜索的模型列表（一家 Provider 可能有上百个模型，所以是懒加载列表）。
 * 底部面板和“能力”页共用；[header] 放在列表最上面。
 * 每组可以折叠：默认只展开当前模型所在的那组（只有一组时展开）；搜索时全部展开。
 */
@Composable
fun ModelPickerContent(
    models: List<ModelOption>,
    isSelected: (ModelOption) -> Boolean,
    onSelect: (ModelOption) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 16.dp),
    header: LazyListScope.() -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(models, query) {
        if (query.isBlank()) models
        else models.filter { it.name.contains(query.trim(), true) || it.provider.contains(query.trim(), true) }
    }
    val groups = remember(shown) { shown.groupBy { it.provider } }
    val opened = remember { mutableStateMapOf<String, Boolean>() }
    fun isOpen(provider: String, group: List<ModelOption>): Boolean =
        query.isNotBlank() || (opened[provider] ?: (groups.size == 1 || group.any(isSelected)))
    LazyColumn(modifier, contentPadding = contentPadding) {
        header()
        if (models.size > 8) {
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索模型或 Provider") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        if (models.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Hermes 没有可选的模型。到 Hermes 那台机器上用 hermes model 配置 Provider 后再来看。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        groups.forEach { (provider, group) ->
            val open = isOpen(provider, group)
            item(key = "h-$provider") {
                ProviderHeader(provider, group, open, current = group.firstOrNull(isSelected)) { opened[provider] = !open }
            }
            if (open) items(group, key = { it.id }) { model ->
                val selected = isSelected(model)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(model) }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = { onSelect(model) })
                    Spacer(Modifier.width(8.dp))
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(model.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                        if (model.note != null) {
                            TagLabel(
                                model.note,
                                container = MaterialTheme.colorScheme.primaryContainer,
                                content = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 一组的标题：Provider 名、几个模型（收起时顺带显示选中的是哪个），点一下展开 / 收起。 */
@Composable
private fun ProviderHeader(provider: String, group: List<ModelOption>, open: Boolean, current: ModelOption?, onToggle: () -> Unit) {
    val turn by animateFloatAsState(if (open) 90f else 0f, label = "provider-chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .semantics { stateDescription = if (open) "已展开" else "已收起" }
            .testTag("provider-$provider")
            .padding(start = 24.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(provider, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                if (!open && current != null) "${group.size} 个模型 · 正在用 ${current.name}" else "${group.size} 个模型",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Rounded.ChevronRight, if (open) "收起 $provider" else "展开 $provider",
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(turn),
        )
    }
}

/** “+”按钮弹出的添加内容面板。 */
@Composable
fun AttachSheet(onPickPhotos: () -> Unit, onCamera: () -> Unit, onPickFiles: () -> Unit, anyFile: Boolean, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        AttachSheetContent(onPickPhotos, onCamera, onPickFiles, anyFile)
    }
}

@Composable
fun AttachSheetContent(onPickPhotos: () -> Unit, onCamera: () -> Unit, onPickFiles: () -> Unit = {}, anyFile: Boolean = false) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
        Text("添加内容", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        AttachRow("相册", "选择一张或多张图片", Icons.Rounded.PhotoLibrary, true, onPickPhotos)
        AttachRow("拍照", "用相机拍一张发给 Hermes", Icons.Rounded.PhotoCamera, true, onCamera)
        AttachRow(
            "文件",
            if (anyFile) "任意文件，原样传到 Hermes 那台电脑上，由它打开处理"
            else "这个连接没有 SSH，发不了文件：在连接设置里打开 SSH 或「文件互通」",
            Icons.Rounded.FolderOpen,
            anyFile,
            onPickFiles,
        )
    }
}

@Composable
private fun AttachRow(title: String, subtitle: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(
            icon,
            container = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            content = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
