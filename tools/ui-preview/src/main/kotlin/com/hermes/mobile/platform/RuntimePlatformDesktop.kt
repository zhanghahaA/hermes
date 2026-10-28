package com.hermes.mobile.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import com.hermes.mobile.data.local.RuntimeControls

/** 桌面显式模拟系统入口，不将其当作 Android 授权、安装或外部窗口已经验证。 */
object DesktopRuntimeActions {
    var lastAction: String? = null
    fun record(action: String) { lastAction = action }
    var resume: () -> Unit = {}
}

@Composable
fun rememberRuntimeControls(refresh: () -> Unit): RuntimeControls {
    val currentRefresh = rememberUpdatedState(refresh)
    DisposableEffect(Unit) {
        DesktopRuntimeActions.resume = { currentRefresh.value() }
        onDispose { DesktopRuntimeActions.resume = {} }
    }
    return RuntimeControls(
    requestPermission = { DesktopRuntimeActions.record("permission"); refresh() },
    openTermux = { DesktopRuntimeActions.record("termux") },
    downloadTermux = { DesktopRuntimeActions.record("download-termux") },
    downloadViewer = { DesktopRuntimeActions.record("download-x11") },
    batterySettings = { DesktopRuntimeActions.record("battery") },
    permissionSettings = { DesktopRuntimeActions.record("permission-settings") },
    storageSettings = { DesktopRuntimeActions.record("storage-settings") },
)
}
