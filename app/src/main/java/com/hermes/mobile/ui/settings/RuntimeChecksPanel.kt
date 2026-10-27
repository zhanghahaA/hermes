package com.hermes.mobile.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.local.*
import com.hermes.mobile.platform.rememberTextCopier
import com.hermes.mobile.platform.appVersion
import com.hermes.mobile.ui.LocalShowMessage

/** 失败项始终展开；已通过项可以收起，避免把下一步按钮挤到很远的位置。 */
@Composable
fun RuntimeChecksPanel(state: LocalRuntimeState, controls: RuntimeControls, initializationCommand: String?) {
    val checks = state.diagnosticChecks()
    var showPassed by remember { mutableStateOf(false) }
    val copy = rememberTextCopier()
    val message = LocalShowMessage.current
    val version = appVersion()
    val failures = checks.filter { it.status == RuntimeCheckStatus.Blocked }
    Text("环境检查结果", style = MaterialTheme.typography.titleMedium)
    Text(when {
        failures.isNotEmpty() -> "发现 ${failures.size} 项未通过，请按下方提示处理。"
        state.probe == RuntimeProbe.Checking -> "基础条件已满足，正在测试 Termux 命令。"
        state.probe == RuntimeProbe.Passed -> "Termux 命令已连通，可以继续准备或安装。"
        else -> "基础条件已满足，点「检查环境」确认命令是否连通。"
    }, color = if (failures.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
    checks.filter { showPassed || it.status != RuntimeCheckStatus.Passed }.forEach { check ->
        Surface(Modifier.fillMaxWidth().testTag("runtime-check-${check.id}"), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.small) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${check.title} · ${check.status.label}", style = MaterialTheme.typography.titleSmall,
                    color = if (check.status == RuntimeCheckStatus.Blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Text(check.detail, style = MaterialTheme.typography.bodySmall)
                check.remedy?.let { remedy ->
                    val label = when (remedy) {
                        RuntimeRemedy.DownloadTermux -> "查看 Termux 下载"
                        RuntimeRemedy.OpenTermux -> "打开 Termux 查看"
                        RuntimeRemedy.Permission -> "去授权执行权限"
                        RuntimeRemedy.PermissionSettings -> "查看 Hermes 系统权限"
                        RuntimeRemedy.Storage -> "打开存储设置"
                        RuntimeRemedy.Initialize -> if (initializationCommand != null) "复制初始化命令并打开 Termux" else "打开 Termux 查看"
                    }
                    TextButton(enabled = !state.busy, onClick = {
                        when (remedy) {
                            RuntimeRemedy.DownloadTermux -> controls.downloadTermux()
                            RuntimeRemedy.OpenTermux -> controls.openTermux()
                            RuntimeRemedy.Permission -> controls.requestPermission()
                            RuntimeRemedy.PermissionSettings -> controls.permissionSettings()
                            RuntimeRemedy.Storage -> controls.storageSettings()
                            RuntimeRemedy.Initialize -> {
                                initializationCommand?.let { copy(it, false); message("已复制，在 Termux 中粘贴并按回车。") }
                                controls.openTermux()
                            }
                        }
                    }) { Text(label) }
                }
            }
        }
    }
    val passed = checks.count { it.status == RuntimeCheckStatus.Passed }
    if (passed > 0) TextButton(onClick = { showPassed = !showPassed }) {
        Text(if (showPassed) "收起已通过项目" else "查看已通过的 $passed 项")
    }
    TextButton(onClick = { copy("App 版本：$version\n" + state.diagnosticReport(), false); message("已复制环境检查结果，不含密钥或原始日志。") }) { Text("复制检查结果") }
}
