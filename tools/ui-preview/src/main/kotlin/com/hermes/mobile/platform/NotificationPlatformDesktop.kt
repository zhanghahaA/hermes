package com.hermes.mobile.platform

import androidx.compose.runtime.Composable

data class NotificationApp(val packageName: String, val name: String)

/** 桌面只模拟授权状态和应用列表，不声称测试了 Android 系统授权或调度。 */
@Composable
fun rememberNotificationAccess(): Pair<Boolean, () -> Unit> = false to {}

@Composable
fun rememberNotificationApps(): List<NotificationApp> = listOf(
    NotificationApp("example.delivery", "快递"),
    NotificationApp("example.calendar", "日历"),
    NotificationApp("example.messages", "消息"),
)
