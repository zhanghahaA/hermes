package com.hermes.mobile.data.local

/** 平台系统页面入口与业务操作分开；内置后端可使用不同的初始化方式。 */
class RuntimeControls(
    val requestPermission: () -> Unit,
    val openTermux: () -> Unit,
    val downloadTermux: () -> Unit,
    val downloadViewer: () -> Unit,
    val batterySettings: () -> Unit,
    val permissionSettings: () -> Unit = {},
    val storageSettings: () -> Unit = {},
)
