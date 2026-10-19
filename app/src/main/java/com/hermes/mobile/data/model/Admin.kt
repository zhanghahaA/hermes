package com.hermes.mobile.data.model

// ---- 管理：记忆、MCP、Gateway 状态、配置文件 ----

/** Hermes 的两份记忆：关于你（USER.md）和它自己的笔记（MEMORY.md）。 */
enum class MemoryTarget(val label: String, val fileName: String) {
    User("关于你", "USER.md"),
    Notes("Hermes 的笔记", "MEMORY.md"),
}

/** 一份记忆：一条条的内容，以及 Hermes 允许的总字数上限。 */
data class MemoryBook(val target: MemoryTarget, val entries: List<String>, val charLimit: Int, val enabled: Boolean = true) {
    /** Hermes 存盘时条目之间用 “\n§\n” 隔开，上限按拼起来的总长度算。 */
    val used: Int get() = entries.joinToString(MemoryDelimiter).length

    companion object {
        const val MemoryDelimiter = "\n§\n"
    }
}

/** config.yaml 里 mcp_servers 下的一项。[url] 和 [command] 二选一。 */
data class McpServer(
    val name: String,
    val url: String? = null,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val enabled: Boolean = true,
) {
    val summary: String get() = url ?: listOfNotNull(command).plus(args).joinToString(" ")
}

/** `/health/detailed` 的要点。 */
data class GatewayStatus(
    val ok: Boolean,
    val state: String?,
    val checks: List<Check>,
    val platforms: List<Platform>,
    val activeAgents: Int?,
    val requestsToday: Int?,
    val messagesToday: Int?,
    val tokensToday: Long?,
    val diskUsedPercent: Double?,
) {
    data class Check(val name: String, val ok: Boolean, val detail: String?)
    data class Platform(val name: String, val state: String, val error: String?)
}

/**
 * 会话分组（和 Hermes 桌面版一样）：会话的工作目录属于某个项目就归到项目名下，
 * 否则按工作目录（git 仓库根目录优先）的文件夹名分组，都没有的归到 [DefaultGroup]。
 */
@kotlinx.serialization.Serializable
data class SessionGrouping(
    /** 显示顺序：项目在前（包括还没有会话的项目），然后是文件夹，最后是“默认”。 */
    val groups: List<String> = emptyList(),
    /** 会话 id → 组名。不在这里的会话属于 [DefaultGroup]。 */
    val of: Map<String, String> = emptyMap(),
    /** Hermes 的项目（可以把会话移进去）。 */
    val projects: List<ProjectInfo> = emptyList(),
    /** 会话 id → 工作目录（项目里的会话让 Agent 在这里干活）。 */
    val folders: Map<String, String> = emptyMap(),
    /** 在 Hermes 里归档了的会话（API 的会话列表不返回它们）。 */
    val archived: List<ArchivedRef> = emptyList(),
) {
    fun groupOf(sessionId: String): String = of[sessionId] ?: DefaultGroup

    companion object {
        const val DefaultGroup = "默认"
    }
}

/** Hermes 的一个项目：[path] 是主文件夹，会话移进项目就是把工作目录设成它。 */
@kotlinx.serialization.Serializable
data class ProjectInfo(val id: String, val name: String, val path: String?)

/** 归档的会话（只知道 id、标题和最后活动时间）。 */
@kotlinx.serialization.Serializable
data class ArchivedRef(val id: String, val title: String, val updatedAt: Long)

/** Bot 屏幕（Hermes 在服务器上给 bot 开的桌面，`computer_use` 和浏览器在上面操作）的状态。 */
data class BotScreenStatus(
    /** 主机能不能跑（只有 Linux 主机有）。 */
    val supported: Boolean,
    val installed: Boolean,
    val running: Boolean,
    val geometry: String?,
    /** 缺软件时要在主机上运行的安装命令。 */
    val installCommand: String?,
    /** 现在不能启动的原因（比如内存不够）。 */
    val blocker: String?,
    val lease: ScreenLease,
)

/** 谁在控制屏幕：默认是 bot；有人接管时 [humanHolds]，[viewerHash] 是接管者 id 的哈希。 */
data class ScreenLease(val humanHolds: Boolean, val viewerHash: String?, val reason: String) {
    /** 是不是 [viewerId] 接管的（Hermes 只公开 id 的 sha256 前 12 位）。 */
    fun heldBy(viewerId: String): Boolean = humanHolds && viewerHash != null && viewerHash == hashViewer(viewerId)

    companion object {
        fun hashViewer(viewerId: String): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(viewerId.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
    }
}

/**
 * 一个 bot（Hermes 的一个 Profile；桌面版叫 Bot）。默认那个叫 "default"。
 * [hasKey]：有自己的 API_SERVER_KEY（手机要经 /p/<名字> 和它聊就得有）。
 */
@kotlinx.serialization.Serializable
data class Bot(
    val name: String,
    val isDefault: Boolean,
    val displayName: String,
    val title: String,
    val description: String,
    val model: String?,
    val hasKey: Boolean,
    /** 服务器上头像文件的路径（profile 目录下的 assets/avatar.*）。 */
    val avatarPath: String?,
    /** 桌面版给 bot 选的颜色（#RRGGBB）。 */
    val color: String?,
    /** 它的固定对话「Bot Chat」。 */
    val canonical: BotSessionRef?,
    /** 最近活跃的对话。 */
    val last: BotSessionRef?,
    /** 头像文件的修改时间：换了头像，缓存就作废。 */
    val avatarVersion: Long = 0,
) {
    /** 显示的名字，和桌面版的顺序一样：Bot 名字（title）→ Profile 的显示名 → 默认 bot 叫 Hermes → id。 */
    val label: String get() = title.ifBlank { displayName }.ifBlank {
        if (isDefault || name == "default") "Hermes" else name.replace(Regex("[-_]+"), " ").trim()
            .split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
    }
}

/** 编辑 bot 时从服务器读到的：描述、性格（SOUL.md）、模型。 */
data class BotDetails(val description: String, val soul: String, val provider: String, val model: String)

/**
 * 新建或修改一个 bot。null 表示这一项不改；新建时 [soul] 为空就按名字和描述自动写一份。
 * [avatar] 是图片的 data URL。
 */
data class BotEdit(
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val soul: String? = null,
    val color: String? = null,
    val avatar: String? = null,
    val clearAvatar: Boolean = false,
    val provider: String? = null,
    val model: String? = null,
    val confirmExpensiveModel: Boolean = false,
)

/** 保存 bot 的结果。 */
sealed interface BotSaved {
    /** 存好了；[needsModel] 不为空表示这个 bot 还没有能用的模型（新建时检查）。 */
    data class Done(val name: String, val needsModel: String? = null) : BotSaved
    /** 选的模型比较贵或有数据政策提醒，用户确认后带 confirmExpensiveModel 再存一次。 */
    data class ConfirmModel(val message: String) : BotSaved
}

/**
 * 用户输入的 bot 名字 → Hermes 接受的 Profile id（只能是小写字母、数字、- 和 _）。
 * 和桌面版一样：带音调的拉丁字母去掉音调，其它文字（比如中文）每个字变成 `u<十六进制>`，
 * 「小助手」→ `u5c0f-u52a9-u624b`。
 */
fun botIdFor(name: String): String {
    val text = java.text.Normalizer.normalize(name.trim(), java.text.Normalizer.Form.NFC)
    val folded = StringBuilder()
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        val ch = String(Character.toChars(cp))
        if (Character.isLetterOrDigit(cp)) {
            val base = java.text.Normalizer.normalize(ch, java.text.Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
            if (base.matches(Regex("[a-zA-Z0-9]+"))) folded.append(base) else folded.append("-u").append(Integer.toHexString(cp)).append('-')
        } else {
            folded.append(ch)
        }
    }
    val slug = folded.toString().replace(Regex("-+"), "-").lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-')
    if (slug.length <= 64) return slug
    val cut = slug.take(64)
    val boundary = if (slug[64] == '-') 64 else cut.lastIndexOf('-')
    return (if (boundary > 0) cut.take(boundary) else cut).trimEnd('-')
}

@kotlinx.serialization.Serializable
data class BotSessionRef(val id: String, val title: String, val lastActive: Long, val preview: String)

/** 所有 bot，以及 gateway 是否同时服务多个 bot（multiplex_profiles）。 */
@kotlinx.serialization.Serializable
data class BotRoster(val bots: List<Bot>, val multiplex: Boolean?, val activeProfile: String?)

/** 开通手机访问的结果：bot 的 key，以及要不要重启 gateway 才生效。 */
data class BotAccess(val key: String, val restartNeeded: Boolean, val changed: List<String>)

/** 切换 bot 的结果。 */
sealed interface BotSwitch {
    data object Done : BotSwitch
    /** 这个 bot 还没有自己的 API Key（或者 gateway 没开 multiplex），要先开通手机访问。 */
    data object NeedsAccess : BotSwitch
    data class Failed(val reason: String) : BotSwitch
}
