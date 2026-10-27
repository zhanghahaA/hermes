package com.hermes.mobile.data.local

import java.util.Locale

enum class RuntimeCheckStatus(val label: String) {
    Passed("已通过"), Blocked("未通过"), Pending("待检查"), Checking("检查中"),
}

enum class RuntimeRemedy { DownloadTermux, OpenTermux, Permission, PermissionSettings, Storage, Initialize }

data class RuntimeCheck(val id: String, val title: String, val status: RuntimeCheckStatus, val detail: String,
                        val remedy: RuntimeRemedy? = null)

enum class RuntimeProbe { NotChecked, Checking, Passed, Failed, TimedOut }

/** 使用与 Android 相同的版本判定，避免预览中直接填 compatible=true 而漏测实际判定。 */
fun supportsTermuxVersion(version: String): Boolean {
    val match = Regex("^(\\d+)\\.(\\d+)(?:\\.|$|[-+])").find(version) ?: return false
    val major = match.groupValues[1].toIntOrNull() ?: return false
    val minor = match.groupValues[2].toIntOrNull() ?: return false
    return major > 0 || minor >= 118
}

fun RuntimeEnvironment.checks(): List<RuntimeCheck> = buildList {
    fun status(passed: Boolean, prerequisite: Boolean = true) = when {
        !prerequisite -> RuntimeCheckStatus.Pending
        passed -> RuntimeCheckStatus.Passed
        else -> RuntimeCheckStatus.Blocked
    }
    add(RuntimeCheck("installed", "Termux 安装", status(installed),
        if (installed) "已识别 ${version.ifBlank { "版本未知" }}${installSource.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"
        else "没有识别到 Termux。安装标准版并打开一次，再返回检查。", if (installed) null else RuntimeRemedy.DownloadTermux))
    add(RuntimeCheck("compatible", "Termux 版本与命令入口", status(compatible && commandServiceAvailable, installed), when {
        !installed -> "安装 Termux 后检查。"
        !compatible -> compatibilityIssue.ifBlank { "需要 GitHub／F-Droid 版 Termux 0.118 或更新版本。" }
        !commandServiceAvailable -> "未找到已启用的命令执行入口。先打开 Termux；如仍失败，请使用标准兼容版本，替换前先备份原数据。"
        else -> "版本符合要求，已找到命令执行入口；实际连通还需下面的命令测试。"
    }, if (installed && (!compatible || !commandServiceAvailable)) RuntimeRemedy.DownloadTermux else null))
    add(RuntimeCheck("permission", "Hermes 执行权限", status(permissionRegistered && permission, installed), when {
        !installed -> "安装 Termux 后才能授权 Hermes 执行命令。"
        !permissionRegistered -> "Android 尚未登记 Termux 的执行权限。先打开 Termux；仍无权限入口时，保留数据并覆盖安装 Hermes，再授权。"
        !permission -> "Hermes 还没有获得执行权限。在 Hermes 的系统权限设置中允许「在 Termux 中执行命令」，然后返回。"
        else -> "Android 已允许 Hermes 请求 Termux 执行命令；Termux 内的外部调用开关需通过命令测试确认。"
    }, when { !installed || permissionRegistered && permission -> null
        !permissionRegistered -> RuntimeRemedy.PermissionSettings
        else -> RuntimeRemedy.Permission }))
    add(RuntimeCheck("architecture", "手机架构", status(arm64),
        if (arm64) "支持 arm64 本机运行包。" else "当前架构 ${architecture.ifBlank { "未能识别" }} 不满足 arm64 要求；可以继续使用远程 Hermes。"))
    val space = String.format(Locale.ROOT, "%.1f GB", freeBytes.coerceAtLeast(0).toDouble() / (1024 * 1024 * 1024))
    add(RuntimeCheck("storage", "安装空间", status(enoughSpace), "内部可用 $space，安装前至少需要 2 GB。" +
        if (enoughSpace) "" else "请在系统存储设置中释放空间后返回。", if (enoughSpace) null else RuntimeRemedy.Storage))
}

fun LocalRuntimeState.diagnosticChecks(): List<RuntimeCheck> = environment.checks() + listOf(
    RuntimeCheck("command", "Termux 命令连通", when {
        environment.blockers.isNotEmpty() -> RuntimeCheckStatus.Pending
        probe == RuntimeProbe.Checking -> RuntimeCheckStatus.Checking
        probe == RuntimeProbe.Passed -> RuntimeCheckStatus.Passed
        probe in setOf(RuntimeProbe.Failed, RuntimeProbe.TimedOut) -> RuntimeCheckStatus.Blocked
        else -> RuntimeCheckStatus.Pending
    }, when {
        environment.blockers.isNotEmpty() -> "先处理上面的必要条件，再测试外部调用与命令回包。"
        probe == RuntimeProbe.Checking -> "正在请求 Termux 执行只读检查，等待回包。"
        probe == RuntimeProbe.Passed -> "已收到 Termux 的检查结果，外部调用与命令回包可用。"
        probe == RuntimeProbe.TimedOut -> "没有及时收到回包，不能确定后台任务是否结束。打开 Termux 查看状态，确认初始化命令已执行，并查看后台与电池限制；不要重复安装。"
        probe == RuntimeProbe.Failed -> "命令测试没有通过。请查看下方错误，确认初始化命令已执行，并打开 Termux 检查；本次没有安装软件。"
        else -> "尚未收到实际命令结果；点「检查环境」测试。复制过初始化命令不等于已经执行成功。"
    }, if (environment.blockers.isEmpty() && probe in setOf(RuntimeProbe.Failed, RuntimeProbe.TimedOut)) RuntimeRemedy.Initialize else null),
    RuntimeCheck("python", "Python 服务环境", when {
        probe != RuntimeProbe.Passed || environment.blockers.isNotEmpty() -> RuntimeCheckStatus.Pending
        snapshot.toolsReady -> RuntimeCheckStatus.Passed
        else -> RuntimeCheckStatus.Pending
    }, if (probe == RuntimeProbe.Passed && snapshot.toolsReady) "服务管理环境可用。"
       else "尚未准备时会在「一键安装 Hermes」中自动安装，不需要手工输入安装代码。")
)

/** 只导出白名单检查项，不复制原始日志、账号、地址、API Key 或命令内容。 */
fun LocalRuntimeState.diagnosticReport(): String = buildString {
    appendLine("Hermes 本机环境检查")
    diagnosticChecks().forEach { appendLine("${it.title}：${it.status.label}\n${it.detail}") }
}

fun LocalRuntimeState.installBlockReason(): String? = when {
    busy -> "正在执行任务，请等待当前任务结束。"
    environment.blockers.isNotEmpty() -> "暂不能继续：${environment.blockers.joinToString("；")}。请按上面的检查项处理后重新检查。"
    !environment.enoughSpace -> "暂不能安装：内部可用空间不足 2 GB，请先释放空间。"
    else -> null
}
