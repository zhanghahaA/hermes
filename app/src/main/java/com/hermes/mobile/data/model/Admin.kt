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
