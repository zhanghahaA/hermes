package com.hermes.mobile.data.model

// ---- 连接 ----

enum class ConnectionType(val label: String, val description: String) {
    Direct("直连", "局域网 / Tailscale / ZeroTier"),
    Https("HTTPS", "反向代理或 Tailscale Serve"),
    Ssh("SSH 隧道", "经 SSH 转发到远端 8642 端口"),
    Local("本机 Termux", "连接手机上的 127.0.0.1"),
}

data class ConnectionProfile(
    val id: String,
    val name: String,
    val type: ConnectionType,
    val host: String,
    val port: Int = 8642,
    val profile: String? = null,
    val sshUser: String? = null,
    val sshPort: Int = 22,
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val latencyMs: Int, val serverVersion: String) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}

enum class TestStepStatus { Pending, Running, Passed, Failed, Skipped }

data class ConnectionTestStep(val title: String, val status: TestStepStatus, val detail: String? = null)

// ---- 会话与消息 ----

/** 会话的来源。只有通过 API Server 创建的会话能在手机上继续聊天，其它来源只读。 */
enum class SessionSource(val label: String) {
    Api("App"),
    Cli("CLI"),
    Tui("TUI"),
    Telegram("Telegram"),
}

data class Session(
    val id: String,
    val title: String,
    val preview: String,
    val updatedAt: Long,
    val source: SessionSource = SessionSource.Api,
    val pinned: Boolean = false,
    val unread: Int = 0,
    val running: Boolean = false,
    val model: String? = null,
) {
    val readOnly: Boolean get() = source != SessionSource.Api
}

enum class Role { User, Assistant }

enum class DeliveryStatus { Sending, Sent, Failed }

/** 图片占位：真实实现里是 Uri / 远端 URL，这里用一个色相值画渐变块。 */
data class ImageRef(val id: String, val hue: Float, val aspect: Float = 1f)

sealed interface ChatItem {
    val id: String
    val time: Long

    data class Message(
        override val id: String,
        override val time: Long,
        val role: Role,
        val text: String,
        val images: List<ImageRef> = emptyList(),
        val status: DeliveryStatus = DeliveryStatus.Sent,
    ) : ChatItem

    /** Hermes 想执行需要批准的操作（对应 /v1/runs/{id}/approval）。 */
    data class Approval(
        override val id: String,
        override val time: Long,
        val summary: String,
        val command: String,
        val choices: List<String> = listOf("允许一次", "本会话内允许", "拒绝"),
        val resolvedChoice: String? = null,
    ) : ChatItem

    /** Hermes 向用户追问（对应 /v1/runs/{id}/clarify）。 */
    data class Clarify(
        override val id: String,
        override val time: Long,
        val question: String,
        val options: List<String>,
        val answer: String? = null,
    ) : ChatItem
}

enum class RunPhase { Idle, Working, AwaitingInput }

// ---- 模型 / 能力 ----

data class ModelOption(val id: String, val name: String, val provider: String, val note: String? = null)

data class Skill(val name: String, val description: String, val category: String)

data class Toolset(val name: String, val description: String, val toolCount: Int, val enabled: Boolean)

// ---- 定时任务 ----

enum class JobRunResult { Success, Failed, Never }

data class CronJob(
    val id: String,
    val name: String,
    val prompt: String,
    val scheduleLabel: String,
    val cron: String,
    val nextRunLabel: String,
    val lastResult: JobRunResult,
    val lastRunLabel: String?,
    val enabled: Boolean,
    val deliverTo: String,
)

// ---- 设置 ----

enum class ThemeMode(val label: String) { System("跟随系统"), Light("浅色"), Dark("深色") }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = false,
    val streamReplies: Boolean = false,
    val showTyping: Boolean = true,
    val enterToSend: Boolean = false,
    val notifyOnReply: Boolean = true,
    val diagnostics: Boolean = false,
    val fontScale: Float = 1f,
)
