package com.hermes.mobile.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class NotificationApp(val packageName: String, val name: String)

/** 桌面只模拟授权状态和应用列表，不声称测试了 Android 系统授权或调度。 */
@Composable
fun rememberNotificationAccess(): Pair<Boolean, () -> Unit> = false to {}

object DesktopNotificationApps {
    var loads = 0
    var beforeLoad: suspend () -> Unit = {}
}

@Composable
fun rememberNotificationApps(): List<NotificationApp>? = produceState<List<NotificationApp>?>(null) {
    value = withContext(Dispatchers.IO) {
        DesktopNotificationApps.loads++
        DesktopNotificationApps.beforeLoad()
        listOf(
    NotificationApp("example.delivery", "快递"),
    NotificationApp("example.calendar", "日历"),
    NotificationApp("example.messages", "消息"),
)
    }
}.value
