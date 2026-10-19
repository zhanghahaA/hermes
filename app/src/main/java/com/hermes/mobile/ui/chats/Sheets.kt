package com.hermes.mobile.ui.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionState
import com.hermes.mobile.ui.components.IconBadge
import com.hermes.mobile.ui.components.StatusDot
import com.hermes.mobile.ui.components.connectionLabel
import com.hermes.mobile.ui.components.icon

data class SheetAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
)

/** 长按菜单统一用底部面板，单手更好点。 */
@Composable
fun ActionSheet(title: String?, actions: List<SheetAction>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ActionSheetContent(title, actions, onDismiss)
    }
}

@Composable
fun ActionSheetContent(title: String?, actions: List<SheetAction>, onDismiss: () -> Unit) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        actions.forEach { action ->
            val color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = action.enabled) {
                        onDismiss()
                        action.onClick()
                    }
                    .alpha(if (action.enabled) 1f else 0.38f)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(action.icon, contentDescription = null, tint = color)
                Spacer(Modifier.width(20.dp))
                Text(action.label, style = MaterialTheme.typography.bodyLarge, color = color)
            }
        }
    }
}

@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, title: String = "重命名会话", onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun ConnectionSwitcherSheet(
    connections: List<ConnectionProfile>,
    activeId: String?,
    state: ConnectionState,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ConnectionSwitcherContent(connections, activeId, state, onSelect, onManage)
    }
}

@Composable
fun ConnectionSwitcherContent(
    connections: List<ConnectionProfile>,
    activeId: String?,
    state: ConnectionState,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
        Text("切换连接", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        connections.forEach { profile ->
            val selected = profile.id == activeId
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(profile.id) }
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(
                    profile.type.icon,
                    container = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    content = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selected) {
                            StatusDot(state, size = 6)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            if (selected) "${profile.type.label} · ${connectionLabel(state)}" else "${profile.type.label} · ${profile.host}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (selected) Icon(Icons.Rounded.CheckCircle, contentDescription = "当前连接", tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onManage,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            Icon(Icons.Rounded.Tune, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("管理连接")
        }
    }
}
