package com.hermes.mobile.data.local

/** 只保存固定分类与数字码；Termux 原始错误可能包含命令、路径或凭据，不能直接导出。 */
enum class RuntimeIssue(val explanation: String) {
    ExternalApps("Termux 尚未允许外部调用。完成一次初始化后返回，App 会自动检查。"),
    Executable("Termux 的命令程序尚不可用。请打开 Termux，等待首次初始化完成后返回。"),
    Rejected("Termux 拒绝了命令请求。请打开 Termux 查看提示，并检查执行权限。"),
    InvalidResult("Termux 返回的结果不完整，请重新检查。"),
    CommandFailed("命令已经返回，但执行失败。请展开检查详情查看运行状态。"),
    LaunchFailed("无法打开 Termux 命令服务，请打开 Termux 后返回重试。"),
    InspectionTimeout("没有收到 Termux 的检查结果。本次只读检查已结束等待，可以打开 Termux 后返回重试，不必重复初始化。"),
    CommandPending("上一项 Termux 操作尚未返回。请打开 Termux 查看进度，安装或配置完成前不要重复执行。"),
}

class RuntimeCommandException(val issue: RuntimeIssue, val resultCode: Int? = null, detail: String? = null) :
    IllegalStateException((detail?.takeIf { it.isNotBlank() } ?: issue.explanation) + (resultCode?.let { "（代码 $it）" } ?: ""))

/** Termux v0.118.3 的 err 使用 Android Activity.RESULT_OK（-1），不是 shell 的成功码 0。 */
fun decodeTermuxResult(errorCode: Int?, exitCode: Int?, stdout: String, stderr: String, errorMessage: String): TermuxCommandResult {
    if (errorCode == null || errorCode == -1 && exitCode == null) {
        return TermuxCommandResult(-1, issue = RuntimeIssue.InvalidResult)
    }
    if (errorCode != -1) {
        val issue = when {
            errorMessage.contains("allow-external-apps", ignoreCase = true) -> RuntimeIssue.ExternalApps
            errorMessage.contains("executable", ignoreCase = true) -> RuntimeIssue.Executable
            else -> RuntimeIssue.Rejected
        }
        return TermuxCommandResult(-1, issue = issue, resultCode = errorCode)
    }
    return TermuxCommandResult(exitCode!!, stdout, stderr)
}
