package com.hermes.mobile.data.remote

import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.model.AppFeature
import com.hermes.mobile.data.model.AppSettings
import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionState
import com.hermes.mobile.data.model.ConnectionTestStep
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.CronJob
import com.hermes.mobile.data.model.ImageRef
import com.hermes.mobile.data.model.ModelOption
import com.hermes.mobile.data.model.Quote
import com.hermes.mobile.data.model.RunPhase
import com.hermes.mobile.data.model.Session
import com.hermes.mobile.data.model.Skill
import com.hermes.mobile.data.model.TestStepStatus
import com.hermes.mobile.data.model.Toolset
import com.hermes.mobile.data.store.LocalStore
import com.hermes.mobile.data.store.SecretBox
import com.hermes.mobile.data.store.StoredState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

/**
 * 连接真实 Hermes 的仓库（阶段 1：连接、会话列表、聊天记录、重命名/置顶/删除）。
 * 发消息、模型、任务等在后续阶段接入，对应的 [features] 还没打开，界面会显示“暂不支持”。
 */
class RemoteHermesRepository(
    private val scope: CoroutineScope,
    private val store: LocalStore,
    private val secrets: SecretBox,
    private val clientFactory: (baseUrl: String, apiKey: String?, profile: String?) -> HermesClient =
        { url, key, profile -> HermesClient(url, key, profile) },
    private val clock: () -> Long = System::currentTimeMillis,
) : HermesRepository {

    private val state = MutableStateFlow(store.load() ?: StoredState())

    private fun persist(transform: (StoredState) -> StoredState) {
        state.update(transform)
        store.save(state.value)
    }

    // ---- 连接 ----

    private val _connections = MutableStateFlow(state.value.connections)
    override val connections: StateFlow<List<ConnectionProfile>> = _connections.asStateFlow()

    private val _activeConnectionId = MutableStateFlow(state.value.activeConnectionId ?: state.value.connections.firstOrNull()?.id)
    override val activeConnectionId: StateFlow<String?> = _activeConnectionId.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _features = MutableStateFlow<Set<AppFeature>>(emptySet())
    override val features: StateFlow<Set<AppFeature>> = _features.asStateFlow()

    /** 当前连接的客户端和能力；切换连接时整体替换。 */
    private var client: HermesClient? = null
    private var capabilities: ServerCapabilities? = null
    private var connectJob: Job? = null

    private fun keyOf(id: String): String? = state.value.keys[id]?.let(secrets::decrypt)

    override fun switchConnection(id: String) {
        _activeConnectionId.value = id
        persist { it.copy(activeConnectionId = id) }
        connect(id)
    }

    private fun connect(id: String) {
        connectJob?.cancel()
        val profile = _connections.value.firstOrNull { it.id == id } ?: return
        _connectionState.value = ConnectionState.Connecting
        _sessions.value = emptyList()
        messages.value = emptyMap()
        connectJob = scope.launch {
            try {
                val newClient = clientFor(profile, keyOf(id))
                val started = clock()
                val health = newClient.health()
                if (health.platform != null && health.platform != "hermes-agent") throw HermesError.NotHermes()
                val caps = newClient.probeCapabilities(health.version)
                client = newClient
                capabilities = caps
                _features.value = featuresFor(caps)
                _connectionState.value = ConnectionState.Connected(
                    latencyMs = (clock() - started).toInt().coerceAtLeast(1),
                    serverVersion = health.knownVersion() ?: "未知版本",
                )
                loadSessions()
            } catch (e: HermesError) {
                client = null
                capabilities = null
                _features.value = emptySet()
                _connectionState.value = ConnectionState.Failed(e.message ?: "连接失败")
            }
        }
    }

    /** 服务器支持、且 App 已经开发完的功能。 */
    private fun featuresFor(caps: ServerCapabilities): Set<AppFeature> = buildSet {
        if (caps.sessionUpdate || caps.sessionDelete) add(AppFeature.ManageSessions)
        // 发消息、模型、技能、任务：后续阶段接入
    }

    override fun saveConnection(profile: ConnectionProfile, apiKey: String?) {
        _connections.update { list ->
            if (list.any { it.id == profile.id }) list.map { if (it.id == profile.id) profile else it } else list + profile
        }
        persist { s ->
            s.copy(
                connections = _connections.value,
                keys = if (apiKey != null) s.keys + (profile.id to secrets.encrypt(apiKey.trim())) else s.keys,
                activeConnectionId = s.activeConnectionId ?: profile.id,
            )
        }
        if (_activeConnectionId.value == null || _activeConnectionId.value == profile.id) {
            switchConnection(profile.id)
        }
    }

    override fun deleteConnection(id: String) {
        _connections.update { list -> list.filterNot { it.id == id } }
        persist { s -> s.copy(connections = _connections.value, keys = s.keys - id) }
        if (_activeConnectionId.value == id) {
            val next = _connections.value.firstOrNull()?.id
            _activeConnectionId.value = next
            persist { it.copy(activeConnectionId = next) }
            if (next != null) connect(next) else {
                connectJob?.cancel()
                client = null
                _sessions.value = emptyList()
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    override fun testConnection(profile: ConnectionProfile, apiKey: String?): Flow<List<ConnectionTestStep>> = flow {
        val key = apiKey?.takeIf { it.isNotBlank() } ?: keyOf(profile.id)
        val titles = listOf("网络可达", "识别 Hermes", "API Key 鉴权", "能力探测")
        var steps = titles.map { ConnectionTestStep(it, TestStepStatus.Pending) }
        suspend fun mark(i: Int, status: TestStepStatus, detail: String? = null) {
            steps = steps.mapIndexed { index, step ->
                when {
                    index == i -> step.copy(status = status, detail = detail)
                    // 某一步失败后，后面的步骤标成跳过
                    status == TestStepStatus.Failed && index > i -> step.copy(status = TestStepStatus.Skipped)
                    else -> step
                }
            }
            emit(steps)
        }
        emit(steps)

        if (profile.type == ConnectionType.Ssh) {
            mark(0, TestStepStatus.Failed, "SSH 隧道在阶段 3 接入")
            return@flow
        }
        val client = try {
            clientFor(profile, key)
        } catch (e: HermesError) {
            mark(0, TestStepStatus.Failed, e.message)
            return@flow
        }

        mark(0, TestStepStatus.Running)
        val started = clock()
        val health = try {
            client.health()
        } catch (e: HermesError.NotHermes) {
            mark(0, TestStepStatus.Passed, "${clock() - started} ms")
            mark(1, TestStepStatus.Failed, e.message)
            return@flow
        } catch (e: HermesError.NotSupported) {
            mark(0, TestStepStatus.Passed, "${clock() - started} ms")
            mark(1, TestStepStatus.Failed, "没有 /health 接口：不是 Hermes，或者版本太旧还没有 API Server")
            return@flow
        } catch (e: HermesError) {
            mark(0, TestStepStatus.Failed, e.message)
            return@flow
        }
        mark(0, TestStepStatus.Passed, "${clock() - started} ms")

        mark(1, TestStepStatus.Running)
        if (health.platform != null && health.platform != "hermes-agent") {
            mark(1, TestStepStatus.Failed, "对方自称 ${health.platform}，不是 Hermes")
            return@flow
        }
        mark(1, TestStepStatus.Passed, health.knownVersion()?.let { "版本 $it" } ?: "版本未知（可能是源码安装）")

        mark(2, TestStepStatus.Running)
        if (key.isNullOrBlank()) {
            mark(2, TestStepStatus.Failed, "还没填 API Key（服务器上的 API_SERVER_KEY）")
            return@flow
        }
        val caps = try {
            client.probeCapabilities(health.version)
        } catch (e: HermesError) {
            mark(2, TestStepStatus.Failed, e.message)
            return@flow
        }
        mark(2, TestStepStatus.Passed)

        mark(3, TestStepStatus.Running)
        if (!caps.sessions) {
            mark(3, TestStepStatus.Failed, "这台 Hermes 太旧，没有会话接口，建议升级（${caps.summary()}）")
        } else {
            mark(3, TestStepStatus.Passed, caps.summary())
        }
    }

    private fun clientFor(profile: ConnectionProfile, key: String?): HermesClient {
        val url = when (profile.type) {
            ConnectionType.Direct -> "http://${profile.host.trim()}:${profile.port}"
            ConnectionType.Local -> "http://127.0.0.1:${profile.port}"
            ConnectionType.Https -> profile.host.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
            ConnectionType.Ssh -> throw HermesError.NotSupported("SSH 隧道（阶段 3 接入）")
        }
        return clientFactory(url, key, profile.profile)
    }

    // ---- 会话 ----

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    override val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    override fun session(id: String): Flow<Session?> = _sessions.map { list -> list.firstOrNull { it.id == id } }

    private suspend fun loadSessions() {
        val c = client ?: return
        if (capabilities?.sessions == false) {
            _sessions.value = emptyList()
            return
        }
        try {
            val now = clock()
            _sessions.value = c.sessions(limit = 100).data
                .filter { it.visibleInList() }
                .map { it.toSession(now) }
        } catch (e: HermesError) {
            _connectionState.value = ConnectionState.Failed(e.message ?: "读取会话失败")
        }
    }

    override suspend fun refreshSessions() {
        if (client == null) {
            _activeConnectionId.value?.let(::connect)
            connectJob?.join()
        } else {
            loadSessions()
        }
    }

    private fun updateSession(id: String, transform: (Session) -> Session) =
        _sessions.update { list -> list.map { if (it.id == id) transform(it) else it } }

    /** 先在本地改（界面立刻变），再发给服务器；失败就改回去并提示。 */
    private fun optimistic(id: String, transform: (Session) -> Session, call: suspend HermesClient.() -> Unit) {
        val before = _sessions.value
        updateSession(id, transform)
        scope.launch {
            try {
                client?.call() ?: throw HermesError.Unreachable("未连接")
            } catch (e: HermesError) {
                _sessions.value = before
                _errors.tryEmit(e.message ?: "操作失败")
            }
        }
    }

    override fun renameSession(id: String, title: String) =
        optimistic(id, { it.copy(title = title) }) { patchSession(id, HermesClient.patch { put("title", title) }) }

    override fun togglePin(id: String) {
        val pinned = _sessions.value.firstOrNull { it.id == id }?.pinned ?: return
        optimistic(id, { it.copy(pinned = !pinned) }) { patchSession(id, HermesClient.patch { put("pinned", !pinned) }) }
    }

    override fun deleteSession(id: String) {
        val before = _sessions.value
        _sessions.update { list -> list.filterNot { it.id == id } }
        scope.launch {
            try {
                client?.deleteSession(id) ?: throw HermesError.Unreachable("未连接")
            } catch (e: HermesError) {
                _sessions.value = before
                _errors.tryEmit(e.message ?: "删除失败")
            }
        }
    }

    override fun createSession(): String? {
        notYet<Unit>("新建会话")
        return null
    }

    override fun forkSession(id: String, fromItemId: String?): String? {
        notYet<Unit>("分叉会话")
        return null
    }

    // ---- 聊天记录 ----

    private val messages = MutableStateFlow<Map<String, List<ChatItem>>>(emptyMap())

    override fun chatItems(sessionId: String): Flow<List<ChatItem>> =
        messages.map { it[sessionId].orEmpty() }.onStart { scope.launch { loadMessages(sessionId) } }

    private suspend fun loadMessages(sessionId: String) {
        val c = client ?: return
        if (capabilities?.sessionMessages == false) return
        try {
            val now = clock()
            val items = c.messages(sessionId).data.mapIndexedNotNull { i, m -> m.toChatItem(i, now) }
            messages.update { it + (sessionId to items) }
        } catch (e: HermesError) {
            _errors.tryEmit(e.message ?: "读取聊天记录失败")
        }
    }

    override fun runPhase(sessionId: String): Flow<RunPhase> = MutableStateFlow(RunPhase.Idle)
    override fun streamingText(sessionId: String): Flow<String?> = MutableStateFlow(null)

    override fun sendMessage(sessionId: String, text: String, images: List<ImageRef>, quote: Quote?) {
        notYet<Unit>("发送消息")
    }

    override fun retryMessage(sessionId: String, itemId: String) = notYet<Unit>("重发")
    override fun regenerate(sessionId: String, itemId: String) = notYet<Unit>("重新生成")
    override fun deleteItem(sessionId: String, itemId: String) = notYet<Unit>("删除单条消息")
    override fun stop(sessionId: String) = Unit
    override fun resolveApproval(sessionId: String, itemId: String, choice: String) = notYet<Unit>("审批")
    override fun answerClarify(sessionId: String, itemId: String, answer: String) = notYet<Unit>("回答追问")

    // ---- 后续阶段的功能 ----

    override val models: StateFlow<List<ModelOption>> = MutableStateFlow(emptyList())
    override val defaultModelId: StateFlow<String> = MutableStateFlow("")
    override fun setSessionModel(sessionId: String, modelId: String) = notYet<Unit>("切换模型")
    override fun setDefaultModel(modelId: String) = notYet<Unit>("切换模型")
    override val skills: StateFlow<List<Skill>> = MutableStateFlow(emptyList())
    override val toolsets: StateFlow<List<Toolset>> = MutableStateFlow(emptyList())
    override fun toggleToolset(name: String) = notYet<Unit>("工具集")
    override val jobs: StateFlow<List<CronJob>> = MutableStateFlow(emptyList())
    override fun setJobEnabled(id: String, enabled: Boolean) = notYet<Unit>("定时任务")
    override fun runJobNow(id: String) = notYet<Unit>("定时任务")
    override fun saveJob(job: CronJob) = notYet<Unit>("定时任务")
    override fun deleteJob(id: String) = notYet<Unit>("定时任务")

    // ---- 设置 ----

    private val _settings = MutableStateFlow(state.value.settings)
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    override fun updateSettings(transform: (AppSettings) -> AppSettings) {
        _settings.update(transform)
        persist { it.copy(settings = _settings.value) }
    }

    // ---- 提示 ----

    private val _errors = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val errors: Flow<String> = _errors

    @Suppress("UNCHECKED_CAST")
    private fun <T> notYet(what: String): T {
        _errors.tryEmit("「$what」会在后续开发阶段接入真实 Hermes")
        return Unit as T
    }

    // 放在最后：自动连接会用到上面所有状态，必须等它们都初始化完（否则重启 App 时会崩溃）
    init {
        _activeConnectionId.value?.let { connect(it) }
    }
}
