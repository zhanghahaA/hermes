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
        ModelPickerContent(models, selectedName, onSelect)
    }
}

/** 按 Provider 分组的模型列表。底部面板和“能力”页共用。 */
@Composable
fun ModelPickerContent(
    models: List<ModelOption>,
    selectedName: String?,
    onSelect: (ModelOption) -> Unit,
    title: String? = "切换模型",
    subtitle: String? = "只对当前会话生效",
) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
        if (title != null) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        models.groupBy { it.provider }.forEach { (provider, group) ->
            SectionHeader(provider, Modifier.padding(start = 4.dp))
            group.forEach { model ->
                val selected = model.name == selectedName
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(model) }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = { onSelect(model) })
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(model.name, style = MaterialTheme.typography.bodyLarge)
                            if (model.note != null) {
                                TagLabel(
                                    model.note,
                                    container = MaterialTheme.colorScheme.primaryContainer,
                                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                        Text(model.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
