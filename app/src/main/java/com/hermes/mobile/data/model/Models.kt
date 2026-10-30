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
    val sshAuth: SshAuthMethod = SshAuthMethod.Key,
    /** 记住的 SSH 主机指纹（`SHA256:...`）。为空表示还没连过，第一次连上时记住。 */
    val sshHostKey: String? = null,
    /** 发给 Hermes 的文件经 SSH 放到这里（不在项目里的对话）；为空用 [DefaultUploadDir]。 */
    val uploadDir: String? = null,
)

/** 默认的文件上传位置：SSH 登录用户主目录下的 hermes-uploads。 */
const val DefaultUploadDir = "~/hermes-uploads"

enum class SshAuthMethod { Key, Password }

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
@Serializable
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

@Serializable
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

@Serializable
enum class Role { User, Assistant }

@Serializable
enum class DeliveryStatus { Sending, Sent, Failed }

/**
 * 消息里的一张图片。[source] 是能读到图片的地方：本机文件路径、`data:` URL 或 http(s) 地址；
 * 读不到（例如服务器上的图、本机没缓存）时为 null，界面用 [hue] 画一个渐变占位块。
 */
@Serializable
data class ImageRef(val id: String, val hue: Float, val aspect: Float = 1f, val source: String? = null)

/** 没发出去的草稿：输入框里的字和选好的附件。 */
data class Draft(val text: String = "", val attachments: List<Attachment> = emptyList()) {
    val isEmpty: Boolean get() = text.isBlank() && attachments.isEmpty()
}

/**
 * 消息里附带的文件。点开时按顺序找内容：[localPath]（手机上的文件）→ [inlineText]（消息里带着的文本）
 * → [remotePath]（Hermes 那台机器上的文件，经 SSH 下载）。
 */
@Serializable
data class FileRef(
    val name: String,
    val sizeBytes: Long? = null,
    val note: String? = null,
    val remotePath: String? = null,
    val localPath: String? = null,
    val inlineText: String? = null,
) {
    val openable: Boolean get() = localPath != null || inlineText != null || remotePath != null
}

/** 输入框里待发送的附件。 */
sealed interface Attachment {
    val id: String

    /** 图片（相册、拍照）。[path] 是压缩后存在本机的文件，文件名就是内容的 SHA-256。 */
    data class Image(override val id: String, val path: String, val aspect: Float) : Attachment

    /**
     * 文件（文本、PDF、文档、压缩包……照片以外的都是）：原样经 SSH 传到 Hermes 那台电脑上，
     * 本机 Termux 存到手机的下载目录，消息里只告诉 Hermes 文件在哪，由它用工具自己打开。
     */
    data class BinaryFile(override val id: String, val name: String, val mime: String, val path: String, val sizeBytes: Long) : Attachment
}

/**
 * 引用。只能引用 Hermes 回复给你的消息正文（[ChatItem.Message.text]），
 * 思考过程、工具调用属于独立的 Activity，不能作为回复引用。
 * [partial] 为 true 表示只引用了选中的一段。
 */
@Serializable
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

    /** 本轮可展开的工作过程；与最终回复分开，不能作为回复引用或发回模型。只保存在当前连接的内存里。 */
    data class Activity(
        override val id: String,
        override val time: Long,
        val steps: List<WorkStep> = emptyList(),
        val earlierReply: String = "",
        val status: ActivityStatus = ActivityStatus.Working,
        /** 这一轮结束的时间（本机时钟），用来显示“用时”。 */
        val finishedAt: Long? = null,
    ) : ChatItem

    /**
     * 一条正式消息。Hermes 的 [text] 是最终回复正文；流式期间的公开旁白和可选工作过程属于 Activity，
     * reasoning / tool.* 不会混进正式回复、引用或发给模型的正文。
     */
    @Serializable
    data class Message(
        override val id: String,
        override val time: Long,
        val role: Role,
        val text: String,
        val images: List<ImageRef> = emptyList(),
        val status: DeliveryStatus = DeliveryStatus.Sent,
        val files: List<FileRef> = emptyList(),
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

enum class ActivityStatus { Working, Completed, Stopped, Failed }

/**
 * 过程里的一步。工具步骤：[title] 是 Hermes 的工具名，[text] 是参数摘要，[output] 是结果摘要
 * （服务器已脱敏、最多 500 字），[durationMs] 是服务器给的耗时。
 */
data class WorkStep(
    val id: String,
    val kind: Kind,
    val title: String,
    val text: String,
    val finished: Boolean = false,
    val failed: Boolean = false,
    val output: String = "",
    val durationMs: Long? = null,
) {
    enum class Kind { Reasoning, Tool, Notice }
}

/** App 在后台时要发通知的事。 */
data class ChatNotice(val sessionId: String, val title: String, val text: String, val kind: Kind) {
    enum class Kind { Reply, NeedsInput, Failed }
}

// ---- 模型 / 能力 ----

/**
 * 一个可选模型。[id] 在列表里唯一（`provider:model`）；[model] / [providerSlug] 是发给 Hermes 的值，
 * [name] / [provider] 是显示用的。
 */
data class ModelOption(
    val id: String,
    val name: String,
    val provider: String,
    val note: String? = null,
    val model: String = name,
    val providerSlug: String = provider,
)

data class Skill(val name: String, val description: String, val category: String) {
    /** 在新对话里指定用这个技能时，预先写进输入框的话。 */
    fun invocation(): String = "/$name "
}

/**
 * 一个工具集。[label] 是 Hermes 给的显示名（可能带 emoji），[configured] 为 false 表示缺 Key，开了也用不了。
 */
data class Toolset(
    val name: String,
    val label: String,
    val description: String,
    val tools: List<String>,
    val enabled: Boolean,
    val configured: Boolean = true,
) {
    val toolCount: Int get() = tools.size
}

// ---- 定时任务 ----

enum class JobRunResult { Success, Failed, Never }

data class CronJob(
    val id: String,
    val name: String,
    val prompt: String,
    val scheduleLabel: String,
    /** 存到 Hermes 的 schedule 原文（cron 表达式、every 30m、ISO 时间……）。 */
    val cron: String,
    val nextRunLabel: String,
    val lastResult: JobRunResult,
    val lastRunLabel: String?,
    val enabled: Boolean,
    /** 结果发到哪（给人看的说法）。 */
    val deliverTo: String,
    /** Hermes 的 deliver 原值：local / telegram / discord …… */
    val deliver: String = "local",
    val lastError: String? = null,
    /** 编辑界面用的结构化时间；null 表示用 [cron] 原文。 */
    val schedule: JobSchedule? = null,
    /** 刚点了“立即运行”，还在等 Hermes 跑完。 */
    val runningNow: Boolean = false,
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
    /** 只展示服务器实际提供的思考与工具过程；不改变模型或服务器配置。 */
    val showWorkProcess: Boolean = false,
    val showTyping: Boolean = true,
    val enterToSend: Boolean = false,
    val notifyOnReply: Boolean = true,
    val diagnostics: Boolean = false,
    val fontScale: Float = 1f,
)
