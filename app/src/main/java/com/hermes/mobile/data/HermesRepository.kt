package com.hermes.mobile.data

import com.hermes.mobile.data.model.AppFeature
import com.hermes.mobile.data.model.AppSettings
import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.ChatNotice
import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionState
import com.hermes.mobile.data.model.ConnectionTestStep
import com.hermes.mobile.data.model.Draft
import com.hermes.mobile.data.model.FileRef
import com.hermes.mobile.data.model.CronJob
import com.hermes.mobile.data.model.Attachment
import com.hermes.mobile.data.model.ModelOption
import com.hermes.mobile.data.model.Quote
import com.hermes.mobile.data.model.RunPhase
import com.hermes.mobile.data.model.Session
import com.hermes.mobile.data.model.Skill
import com.hermes.mobile.data.model.Toolset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/**
 * UI 只依赖这个接口。骨架阶段由 [com.hermes.mobile.data.fake.FakeHermesRepository] 提供假数据，
 * 之后换成真正走 Hermes API Server 的实现即可，界面代码不用改。
 */
interface HermesRepository {
    val localRuntime: com.hermes.mobile.data.local.LocalRuntimeManager? get() = null
    val notificationAssistant: com.hermes.mobile.data.notifications.NotificationAssistant? get() = null
    suspend fun createNotificationSession(target: com.hermes.mobile.data.notifications.NotificationTarget): String? = null

    // 连接
    val connections: StateFlow<List<ConnectionProfile>>
    val activeConnectionId: StateFlow<String?>
    val connectionState: StateFlow<ConnectionState>
    fun switchConnection(id: String)
    /**
     * [apiKey] / [sshSecret] 为 null 表示不改已保存的。
     * [sshSecret] 是 SSH 私钥全文（密钥登录）或密码（密码登录），按 [ConnectionProfile.sshAuth] 解释。
     */
    fun saveConnection(profile: ConnectionProfile, apiKey: String?, sshSecret: String? = null)
    /** 本机部署先保存凭据，接口检查通过后再切换，不提前改变当前连接。 */
    fun saveConnectionWithoutActivating(profile: ConnectionProfile, apiKey: String) {
        error("当前仓库不支持预存本机连接")
    }
    fun deleteConnection(id: String)
    /** [apiKey] / [sshSecret] 为 null 时用已保存的。 */
    fun testConnection(profile: ConnectionProfile, apiKey: String?, sshSecret: String? = null): Flow<List<ConnectionTestStep>>
    /** 已保存的 SSH 私钥对应的公钥（一行，可以直接贴进 authorized_keys）；没有就是 null。 */
    fun sshPublicKey(connectionId: String): String? = null
    /** 当前连接能用的功能。 */
    val features: StateFlow<Set<AppFeature>>

    // 会话
    val sessions: StateFlow<List<Session>>
    fun session(id: String): Flow<Session?>
    suspend fun refreshSessions()
    /** 返回新会话 id；当前连接不支持时返回 null（并通过 [errors] 说明原因）。 */
    fun createSession(): String?
    fun renameSession(id: String, title: String)
    fun deleteSession(id: String)
    fun togglePin(id: String)
    fun forkSession(id: String, fromItemId: String?): String?

    // 聊天
    fun chatItems(sessionId: String): Flow<List<ChatItem>>
    fun runPhase(sessionId: String): Flow<RunPhase>
    fun streamingText(sessionId: String): Flow<String?>
    /**
     * 发消息。带 [quote] 时，真实实现会把引用内容以 Markdown 引用块（"> ..."）放在正文前面发给 Hermes，
     * 让它知道你在追问哪一段。
     */
    fun sendMessage(sessionId: String, text: String, attachments: List<Attachment>, quote: Quote? = null)
    /** 当前连接能不能发任意文件（SSH 上传到服务器、本机 Termux 存到下载目录）。图片、PDF、文本文件不受限制。 */
    fun canSendAnyFile(): Boolean = false

    /** 各会话没发出去的草稿文字（会话列表里显示“[草稿]”）。 */
    val drafts: StateFlow<Map<String, String>> get() = NoDrafts
    fun draft(sessionId: String): Draft = Draft()
    fun saveDraft(sessionId: String, draft: Draft) = Unit

    /**
     * 服务器上的文件（Hermes 生成的图片、聊天记录里的原图）下载到本机，返回本机路径。
     * 当前连接拿不到（没有 SSH）时返回 null。
     */
    suspend fun fetchRemoteMedia(path: String): String? = null

    /** 消息里的文件准备好给系统打开：返回本机路径；拿不到（服务器上的文件但没有 SSH）返回 null。 */
    suspend fun fileForOpening(file: FileRef): String? = file.localPath?.takeIf { java.io.File(it).isFile }
    fun retryMessage(sessionId: String, itemId: String)
    fun deleteItem(sessionId: String, itemId: String)
    fun stop(sessionId: String)
    fun resolveApproval(sessionId: String, itemId: String, choice: String)
    fun answerClarify(sessionId: String, itemId: String, answer: String)

    // 模型与能力
    val models: StateFlow<List<ModelOption>>
    val defaultModelId: StateFlow<String>
    fun setSessionModel(sessionId: String, modelId: String)
    fun setDefaultModel(modelId: String)
    val skills: StateFlow<List<Skill>>
    /** 读技能列表出错时的说明（有的 Hermes 版本这个接口本身有问题）。 */
    val skillsError: StateFlow<String?>
    val toolsets: StateFlow<List<Toolset>>
    fun toggleToolset(name: String)
    /** 能不能在手机上开关工具集（要有 SSH）。 */
    fun canToggleToolsets(): Boolean = false
    /** 正在开关的工具集。 */
    val toolsetsBusy: StateFlow<Set<String>>
    /** 重新读技能和工具集（打开“能力”页时）。 */
    suspend fun refreshCapabilities() = Unit

    /** 给“导出诊断信息”用的一段文字：连接、服务器能力、最近的错误。不含 Key、密码、私钥。 */
    fun diagnosticsReport(): String = "（演示数据）"

    /** 会话分组（项目 / 工作目录），要 SSH 才读得到；读不到时只有空分组。 */
    val sessionGroups: StateFlow<com.hermes.mobile.data.model.SessionGrouping>
        get() = kotlinx.coroutines.flow.MutableStateFlow(com.hermes.mobile.data.model.SessionGrouping())

    /** 归档了的会话（Hermes 的会话列表不返回它们，App 自己记 + 经 SSH 读）。 */
    val archivedSessions: StateFlow<List<Session>>
        get() = kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    fun setArchived(id: String, archived: Boolean) = Unit
    /** 把会话移进 Hermes 项目（改它的工作目录，和桌面版拖动一样）；null 是移出项目。 */
    fun moveToProject(sessionId: String, project: com.hermes.mobile.data.model.ProjectInfo?) = Unit
    /** 新对话还没发第一条消息时选的项目：建好会话后移进去。 */
    fun setDraftProject(draftId: String, project: com.hermes.mobile.data.model.ProjectInfo?) = Unit
    /** 重新读分组（新建项目之后）。 */
    suspend fun refreshGroups() = Unit

    // 管理
    /** 经 SSH 的管理功能（记忆、MCP、配置文件、日志）。连接没配 SSH 时为 null。 */
    fun admin(): com.hermes.mobile.data.remote.AdminApi? = null

    // ---- Bot（Hermes 的 Profile；桌面版叫 Bot） ----

    /** 当前连接上的所有 bot；读不到（没有 SSH 等）是 null。 */
    val bots: StateFlow<com.hermes.mobile.data.model.BotRoster?> get() = kotlinx.coroutines.flow.MutableStateFlow(null)
    /** 正在用的 bot 的名字（"default" 是默认那个）。 */
    val activeBot: StateFlow<String?> get() = kotlinx.coroutines.flow.MutableStateFlow(null)
    /** bot 列表读取出错的原因。 */
    val botsError: StateFlow<String?> get() = kotlinx.coroutines.flow.MutableStateFlow(null)
    suspend fun refreshBots() = Unit
    /** 换成和 [name] 这个 bot 聊（同一个连接、同一条 SSH）。 */
    suspend fun switchBot(name: String): com.hermes.mobile.data.model.BotSwitch = com.hermes.mobile.data.model.BotSwitch.Failed("不支持")
    /** 开通 bot 的手机访问（生成它自己的 key、打开 multiplex、重启 gateway），然后换过去。[progress] 报告进度；返回错误或 null。 */
    suspend fun enableBotAccess(name: String, progress: (String) -> Unit = {}): String? = "不支持"
    /** 当前 bot 的固定对话「Bot Chat」：有就返回它的 id，没有就新建一个（草稿，发第一条时建到服务器上）。 */
    fun openBotChat(): String? = null
    /** 读 bot 头像（服务器上的文件，读过的存在手机上）。 */
    suspend fun botAvatar(bot: com.hermes.mobile.data.model.Bot): ByteArray? = null
    /** 编辑页要的详细信息（描述、性格、模型），经 SSH 读。 */
    suspend fun botDetails(name: String): Result<com.hermes.mobile.data.model.BotDetails> = Result.failure(IllegalStateException("不支持"))
    /** 新建（[create]）或修改 bot，成功后刷新列表。 */
    suspend fun saveBot(edit: com.hermes.mobile.data.model.BotEdit, create: Boolean): Result<com.hermes.mobile.data.model.BotSaved> = Result.failure(IllegalStateException("不支持"))
    /** 删除 bot；正在用它时先换回默认 bot。返回错误或 null。 */
    suspend fun deleteBot(name: String): String? = "不支持"
    /** 群聊列表（多个 bot 在一个房间里讨论）；读不到是 null。 */
    val rooms: StateFlow<List<com.hermes.mobile.data.model.Room>?> get() = kotlinx.coroutines.flow.MutableStateFlow(null)
    val roomsError: StateFlow<String?> get() = kotlinx.coroutines.flow.MutableStateFlow(null)
    suspend fun refreshRooms() = Unit
    /** 群聊的操作（经 SSH 连 gateway）；没有 SSH 时为 null。 */
    fun roomApi(): com.hermes.mobile.data.remote.RoomApi? = null
    /** gateway 状态（`/health/detailed`，不需要 SSH）。 */
    suspend fun gatewayStatus(): com.hermes.mobile.data.model.GatewayStatus? = null

    // 定时任务
    val jobs: StateFlow<List<CronJob>>
    suspend fun refreshJobs() = Unit
    /** 某个定时任务跑出来的会话（Hermes 每跑一次建一个会话，标题是“任务名 · 时间”），新的在前。 */
    fun jobRuns(job: CronJob): Flow<List<Session>> = sessions.map { list ->
        list.filter { it.source.id == "cron" && it.title.startsWith(job.name + " · ") }.sortedByDescending { it.updatedAt }
    }
    fun setJobEnabled(id: String, enabled: Boolean)
    fun runJobNow(id: String)
    fun saveJob(job: CronJob)
    fun deleteJob(id: String)

    /** 正在等 Hermes 回复的会话。Android 上不为空时保持一个前台服务，切到后台也不断线。 */
    val activeRuns: StateFlow<Set<String>> get() = NoActiveRuns
    /** 回复到了、需要审批……App 在后台时据此发通知。 */
    val notices: Flow<ChatNotice> get() = emptyFlow()

    /** 要提示给用户的错误或说明（一次性事件）。 */
    val errors: Flow<String>

    // 设置
    val settings: StateFlow<AppSettings>
    fun updateSettings(transform: (AppSettings) -> AppSettings)
}

private val NoDrafts: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap<String, String>()).asStateFlow()
private val NoActiveRuns: StateFlow<Set<String>> = MutableStateFlow(emptySet<String>()).asStateFlow()
