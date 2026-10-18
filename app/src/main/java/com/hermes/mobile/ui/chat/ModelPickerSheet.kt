package com.hermes.mobile.ui.chat

import androidx.compose.foundation.clickable
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
import com.hermes.mobile.ui.components.SectionHeader
import com.hermes.mobile.ui.components.TagLabel

@Composable
fun ModelPickerSheet(
    models: List<ModelOption>,
    selectedName: String?,
    onSelect: (ModelOption) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ModelPickerContent(
            models = models,
            isSelected = { it.model == selectedName || it.name == selectedName },
            onSelect = onSelect,
            header = {
                item(key = "title") {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                        Text("切换模型", style = MaterialTheme.typography.titleLarge)
                        Text("只对这个对话生效", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

/**
 * 按 Provider 分组、可以搜索的模型列表（一家 Provider 可能有上百个模型，所以是懒加载列表）。
 * 底部面板和“能力”页共用；[header] 放在列表最上面。
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
        shown.groupBy { it.provider }.forEach { (provider, group) ->
            item(key = "h-$provider") { SectionHeader(provider, Modifier.padding(start = 4.dp)) }
            items(group, key = { it.id }) { model ->
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
            if (anyFile) "任意文件；PDF 和文本文件 Hermes 能直接看到内容" else "PDF、文本、代码、表格等；其它格式需要 SSH 或本机连接",
            Icons.Rounded.FolderOpen,
            true,
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
