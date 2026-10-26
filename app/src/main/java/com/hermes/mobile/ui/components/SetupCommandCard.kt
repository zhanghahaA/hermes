package com.hermes.mobile.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.mobile.platform.rememberTextCopier
import com.hermes.mobile.ui.LocalShowMessage
import com.hermes.mobile.ui.theme.CodeTextStyle

/** 命令默认收起；复制后直接告诉用户在哪里执行，不要求理解代码。 */
@Composable
fun SetupCommandCard(title: String, destination: String, command: String, sensitive: Boolean = false,
    copyLabel: String = "复制命令", next: String = "粘贴后按回车，完成后回到 Hermes。", contentName: String = "命令", onCopied: () -> Unit = {}) {
    val copy = rememberTextCopier()
    val message = LocalShowMessage.current
    var expanded by remember(command) { mutableStateOf(false) }
    var copied by remember(command) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(destination, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { copy(command, sensitive); copied = true; message("已复制。$destination"); onCopied() }) { Text(copyLabel) }
            if (copied) Text("已复制 · $next", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起$contentName" else "查看${contentName}内容") }
            if (expanded) SelectionContainer { Text(command, style = CodeTextStyle) }
        }
    }
}
