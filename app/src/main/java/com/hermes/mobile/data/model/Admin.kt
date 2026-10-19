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
