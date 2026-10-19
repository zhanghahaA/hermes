package com.hermes.mobile.ui.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.model.ProjectInfo
import com.hermes.mobile.data.remote.AdminApi
import com.hermes.mobile.data.remote.AdminException
import kotlinx.coroutines.launch

/** “移到项目…”：选一个项目，或者移出项目，或者新建一个再移进去。 */
@Composable
fun ProjectPickerSheet(
    title: String,
    projects: List<ProjectInfo>,
    current: String?,
    onPick: (ProjectInfo?) -> Unit,
    onNewProject: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ProjectPickerContent(title, projects, current, onPick = { onDismiss(); onPick(it) }, onNewProject = onNewProject?.let { { onDismiss(); it() } })
    }
}

@Composable
fun ProjectPickerContent(
    title: String,
    projects: List<ProjectInfo>,
    current: String?,
    onPick: (ProjectInfo?) -> Unit,
    onNewProject: (() -> Unit)?,
) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
        Text("移到项目", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        Text(
            "「$title」之后会在项目的文件夹里干活",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        val movable = projects.filter { it.path != null }
        if (movable.isEmpty()) {
            Text(
                "还没有项目。新建一个，选好它的文件夹，就能把对话放进去。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
        movable.forEach { project ->
            val selected = project.name == current
            PickerRow(
                icon = Icons.Rounded.Folder,
                label = project.name,
                detail = project.path,
                selected = selected,
                onClick = { if (!selected) onPick(project) },
            )
        }
        if (current != null && movable.any { it.name == current }) {
            PickerRow(Icons.Rounded.FolderOff, "移出项目", "回到“默认”", selected = false) { onPick(null) }
        }
        if (onNewProject != null) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            PickerRow(Icons.Rounded.CreateNewFolder, "新建项目…", null, selected = false, onClick = onNewProject)
        }
    }
}

@Composable
private fun PickerRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) Icon(Icons.Rounded.CheckCircle, contentDescription = "当前项目", tint = MaterialTheme.colorScheme.primary)
    }
}

/**
 * 新建项目：起个名字，再在电脑上挑一个文件夹（经 SSH 一层层往下点）。
 * 建好后调 [onCreated]（名字、文件夹）。
 */
@Composable
fun NewProjectDialog(admin: AdminApi, onDismiss: () -> Unit, onCreated: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var path by rememberSaveable { mutableStateOf<String?>(null) }
    var folders by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // path 为 null 时从家目录开始；服务器返回的是规整后的绝对路径
    var request by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(request) {
        folders = null
        error = null
        try {
            val (dir, children) = admin.listFolders(request)
            path = dir
            folders = children
        } catch (e: AdminException) {
            error = e.message
            folders = emptyList()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        title = { Text("新建项目") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("项目名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("文件夹（Agent 在这里干活）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                path ?: "…",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.StartEllipsis,
                            )
                        }
                        HorizontalDivider()
                        Box(Modifier.heightIn(min = 120.dp, max = 240.dp)) {
                            val list = folders
                            if (list == null) {
                                CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp))
                            } else {
                                LazyColumn {
                                    val current = path
                                    if (current != null && current != "/") {
                                        item(key = "..") {
                                            FolderRow(Icons.Rounded.ArrowUpward, "上一级") {
                                                request = current.substringBeforeLast('/').ifEmpty { "/" }
                                            }
                                        }
                                    }
                                    items(list, key = { it }) { child ->
                                        FolderRow(Icons.Rounded.Folder, child) {
                                            request = (current ?: "").trimEnd('/') + "/" + child
                                        }
                                    }
                                    if (list.isEmpty() && error == null) {
                                        item(key = "none") {
                                            Text(
                                                "这里没有子文件夹",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && path != null && folders != null && !creating,
                onClick = {
                    val folder = path ?: return@TextButton
                    creating = true
                    error = null
                    scope.launch {
                        try {
                            admin.createProject(name.trim(), folder)
                            onCreated(name.trim(), folder)
                        } catch (e: AdminException) {
                            error = e.message
                        } finally {
                            creating = false
                        }
                    }
                },
            ) { Text(if (creating) "正在新建…" else "新建") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !creating) { Text("取消") } },
    )
}

@Composable
private fun FolderRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
