package com.hermes.mobile.data.local

/** 安装页一次只给一个下一步。系统授权仍由用户完成，安装意愿由安装按钮表达。 */
enum class RuntimeSetupStep(val title: String, val button: String, val detail: String) {
    Busy("正在处理", "请稍候", ""),
    Download("先安装运行环境", "下载 Termux", "安装 Termux 并打开一次，然后回到这里。"),
    Unsupported("这台设备暂不支持本机安装", "暂不支持", "本机安装需要 arm64 手机，仍可连接电脑上的 Hermes。"),
    PermissionSettings("需要登记执行权限", "打开权限设置", "请先打开一次 Termux，再允许 Hermes 在 Termux 中执行命令。"),
    Permission("允许 Hermes 安装和运行", "授予权限", "在系统弹窗中允许执行命令。返回后会自动检查。"),
    Initialize("还差一次初始化", "复制命令并打开 Termux", "允许 Hermes 调用 Termux：打开后长按粘贴，按回车，再返回这里。只需做一次。"),
    Check("检查连接是否可用", "重新检查", "返回 App 后自动检查；也可点下面的按钮重试。"),
    Storage("安装空间不足", "释放存储空间", "安装需要至少 2 GB 可用空间，已有数据不会删除。"),
    Install("准备好安装了", "安装 Hermes", "自动检查、准备依赖、安装并配置手机连接。完成后直接选择模型。"),
    Configure("继续完成安装", "完成安装配置", "复用已经安装的 Hermes，补齐运行环境和手机连接，不重新下载安装包。"),
    Connect("Hermes 已安装", "开始聊天", "选择好模型后，即可启动并连接。"),
}

fun LocalRuntimeState.setupStep(): RuntimeSetupStep = when {
    busy -> RuntimeSetupStep.Busy
    !environment.installed || !environment.compatible || !environment.commandServiceAvailable -> RuntimeSetupStep.Download
    !environment.arm64 -> RuntimeSetupStep.Unsupported
    !environment.permissionRegistered -> RuntimeSetupStep.PermissionSettings
    !environment.permission -> RuntimeSetupStep.Permission
    issue == RuntimeIssue.ExternalApps -> RuntimeSetupStep.Initialize
    !checked || probe != RuntimeProbe.Passed -> RuntimeSetupStep.Check
    (!snapshot.toolsReady || !snapshot.hermesInstalled) && !environment.enoughSpace -> RuntimeSetupStep.Storage
    !snapshot.hermesInstalled -> RuntimeSetupStep.Install
    !snapshot.configured || !snapshot.toolsReady -> RuntimeSetupStep.Configure
    else -> RuntimeSetupStep.Connect
}
