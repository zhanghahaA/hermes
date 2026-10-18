package com.hermes.mobile.data.model

import kotlinx.serialization.Serializable

// ---- 连接 ----

enum class ConnectionType(val label: String, val description: String) {
    Direct("直连", "局域网 / Tailscale / ZeroTier"),
    Https("HTTPS", "反向代理或 Tailscale Serve"),
    Ssh("SSH 隧道", "经 SSH 转发到远端 8642 端口"),
    Local("本机 Termux", "连接手机上的 127.0.0.1"),
}

@Serializable
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

/**
 * 会话的来源，对应服务器返回的 `source` 字段。不同版本的 Hermes 可能出现新的来源，
 * 所以不用枚举：认识的给个好看的名字，不认识的原样显示。
 */
data class SessionSource(val id: String) {
    val label: String
        get() = when (id.lowercase()) {
            "api_server", "api", "app" -> "App"
            "cli" -> "CLI"
            "tui" -> "TUI"
            "telegram" -> "Telegram"
            "discord" -> "Discord"
            "slack" -> "Slack"
            "cron" -> "定时任务"
            "acp" -> "编辑器"
            "" -> "未知"
            else -> id
        }

    companion object {
        val Api = SessionSource("api_server")
        val Cli = SessionSource("cli")
        val Tui = SessionSource("tui")
        val Telegram = SessionSource("telegram")
    }
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
    /** 服务器拒绝从手机继续这个会话（返回过 409），只能看或分叉。 */
    val readOnly: Boolean = false,
) {
    /** 不是在 App / API 里建的会话（CLI、Telegram……），列表里显示来源标签。 */
    val external: Boolean get() = source != SessionSource.Api
}

enum class Role { User, Assistant }

enum class DeliveryStatus { Sending, Sent, Failed }

/** 图片占位：真实实现里是 Uri / 远端 URL，这里用一个色相值画渐变块。 */
data class ImageRef(val id: String, val hue: Float, val aspect: Float = 1f)

/**
 * 引用。只能引用 Hermes 回复给你的消息正文（[ChatItem.Message.text]），
 * 思考过程、工具调用这些从来不进消息列表，所以也不可能被引用。
 * [partial] 为 true 表示只引用了选中的一段。
 */
data class Quote(val messageId: String, val text: String, val partial: Boolean) {
    companion object {
        /** 只有 Hermes 已完成的回复可以引用；其它消息返回 null。 */
        fun of(message: ChatItem.Message, selected: String? = null): Quote? {
            if (!message.quotable) return null
            val text = selected?.trim()?.takeIf { it.isNotEmpty() } ?: message.text.trim()
            return Quote(message.id, text, partial = selected != null && text != message.text.trim())
        }
    }
}

sealed interface ChatItem {
    val id: String
    val time: Long

    /**
     * 一条消息。Hermes 的消息 [text] 只有最终回复正文：接入 API 时只拼接 assistant.delta，
     * reasoning / commentary / tool.* 事件一律丢弃，不进这里。
     */
    data class Message(
        override val id: String,
        override val time: Long,
        val role: Role,
        val text: String,
        val images: List<ImageRef> = emptyList(),
        val status: DeliveryStatus = DeliveryStatus.Sent,
        /** 这条（用户）消息引用了哪条回复。 */
        val quote: Quote? = null,
    ) : ChatItem {
        val quotable: Boolean get() = role == Role.Assistant && status == DeliveryStatus.Sent && text.isNotBlank()
    }

    /** Hermes 想执行需要批准的操作（对应 /v1/runs/{id}/approval）。 */
    data class Approval(
        override val id: String,
        override val time: Long,
        val summary: String,
        val command: String,
        /** 服务器给的选项 id：once / session / always / deny（界面上显示成中文）。 */
        val choices: List<String> = listOf("once", "session", "deny"),
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

/** App 在后台时要发通知的事。 */
data class ChatNotice(val sessionId: String, val title: String, val text: String, val kind: Kind) {
    enum class Kind { Reply, NeedsInput, Failed }
}

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

// ---- 当前连接能用的功能 ----

/** 界面按这个决定显示还是提示“暂不支持”。由服务器能力和 App 的开发进度共同决定。 */
enum class AppFeature { SendMessages, ManageSessions, Models, Skills, Toolsets, Jobs }

// ---- 设置 ----

enum class ThemeMode(val label: String) { System("跟随系统"), Light("浅色"), Dark("深色") }

@Serializable
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
