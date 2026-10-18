package com.hermes.mobile.data.remote

import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.model.Attachment
import com.hermes.mobile.data.model.MaxImagesPerMessage
import com.hermes.mobile.data.model.displayFiles
import com.hermes.mobile.data.model.displayImages
import com.hermes.mobile.data.model.imageCount
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.util.Base64
import com.hermes.mobile.data.model.AppFeature
import com.hermes.mobile.data.model.AppSettings
import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.ChatNotice
import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionState
import com.hermes.mobile.data.model.ConnectionTestStep
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.CronJob
import com.hermes.mobile.data.model.DeliveryStatus
import com.hermes.mobile.data.model.ModelOption
import com.hermes.mobile.data.model.Quote
import com.hermes.mobile.data.model.Role
import com.hermes.mobile.data.model.RunPhase
import com.hermes.mobile.data.model.Session
import com.hermes.mobile.data.model.Skill
import com.hermes.mobile.data.model.SshAuthMethod
import com.hermes.mobile.data.model.TestStepStatus
import com.hermes.mobile.data.model.Toolset
import com.hermes.mobile.data.ssh.SshAuth
import com.hermes.mobile.data.ssh.SshException
import com.hermes.mobile.data.ssh.SshKeys
import com.hermes.mobile.data.ssh.SshTunnel
import com.hermes.mobile.data.store.LocalStore
import com.hermes.mobile.data.store.SecretBox
import com.hermes.mobile.data.store.StoredRun
import com.hermes.mobile.data.store.StoredState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 连接真实 Hermes 的仓库。
 *
 * 阶段 1：连接、会话列表、聊天记录、重命名/置顶/删除。
 * 阶段 2：发消息（走 /v1/runs，断线不会打断 Hermes）、断线重连、审批和追问卡片、停止、新建和分叉会话。
 * 模型、任务等在后续阶段接入，对应的 [features] 还没打开，界面会显示“暂不支持”。
 */
class RemoteHermesRepository(
    private val scope: CoroutineScope,
    private val store: LocalStore,
    private val secrets: SecretBox,
    private val clientFactory: (baseUrl: String, apiKey: String?, profile: String?) -> HermesClient =
        { url, key, profile -> HermesClient(url, key, profile) },
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * 发过的图片缓存在这里，文件名是内容的 SHA-256（和 Hermes 服务器上的目录名一致），
     * 读聊天记录时据此找回原图。为 null 时历史里的图片显示占位块。
     */
    private val mediaDir: File? = null,
    /**
     * 本机 Termux 连接发普通文件时，把文件存到 Termux 也能读到的地方（Android 上是“下载/Hermes”），
     * 返回 Termux 里看到的路径；不支持时返回 null。
     */
    private val localFileExporter: ((path: String, name: String, mime: String) -> String?)? = null,
    /** 断线重连的等待时间（毫秒），第 n 次失败等多久。测试里调短。 */
    private val backoff: (attempt: Int) -> Long = { attempt -> (1000L shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(15_000) },
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
    private var connectedId: String? = null
    private var connectJob: Job? = null

    /** 当前连接上所有“等回复”的协程。切换连接时整体取消。 */
    private var runScope = newRunScope()

    private fun newRunScope() = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private fun keyOf(id: String): String? = state.value.keys[id]?.let(secrets::decrypt)
    private fun sshSecretOf(id: String): String? = state.value.keys[SshSecretPrefix + id]?.let(secrets::decrypt)

    /** 当前连接的 SSH 隧道（不是 SSH 连接时为 null）。 */
    @Volatile private var tunnel: SshTunnel? = null

    /** “测试连接”时第一次见到的 SSH 主机指纹，保存连接时记住。 */
    private val seenHostKeys = ConcurrentHashMap<String, String>()

    override fun switchConnection(id: String) {
        _activeConnectionId.value = id
        persist { it.copy(activeConnectionId = id) }
        connect(id)
    }

    private fun connect(id: String) {
        connectJob?.cancel()
        runScope.cancel()
        runScope = newRunScope()
        runs.clear()
        controls.clear()
        outbox.clear()
        _activeRuns.value = emptySet()
        closeTunnel()
        val profile = _connections.value.firstOrNull { it.id == id } ?: return
        _connectionState.value = ConnectionState.Connecting
        _sessions.value = emptyList()
        chats.value = emptyMap()
        connectJob = scope.launch {
            try {
                val started = clock()
                val newClient = openClient(profile, keyOf(id), sshSecretOf(id)) { fingerprint -> pinHostKey(id, fingerprint) }
                val health = newClient.health()
                if (health.platform != null && health.platform != "hermes-agent") throw HermesError.NotHermes()
                val caps = newClient.probeCapabilities(health.version)
                client = newClient
                capabilities = caps
                connectedId = id
                _features.value = featuresFor(caps)
                _connectionState.value = ConnectionState.Connected(
                    latencyMs = (clock() - started).toInt().coerceAtLeast(1),
                    serverVersion = health.knownVersion() ?: "未知版本",
                )
                loadSessions()
                resumeStoredRuns(id)
            } catch (e: HermesError) {
                client = null
                capabilities = null
                connectedId = null
                _features.value = emptySet()
                _connectionState.value = ConnectionState.Failed(e.message ?: "连接失败")
            }
        }
    }

    private fun closeTunnel() {
        val old = tunnel ?: return
        tunnel = null
        scope.launch(Dispatchers.IO) { old.close() }
    }

    /** 第一次连上 SSH 服务器：记住它的指纹，以后不一致就拒绝连接。 */
    private fun pinHostKey(connectionId: String, fingerprint: String) {
        _connections.update { list -> list.map { if (it.id == connectionId && it.sshHostKey == null) it.copy(sshHostKey = fingerprint) else it } }
        persist { it.copy(connections = _connections.value) }
    }

    /** 服务器支持、且 App 已经开发完的功能。 */
    private fun featuresFor(caps: ServerCapabilities): Set<AppFeature> = buildSet {
        if (caps.sessionUpdate || caps.sessionDelete) add(AppFeature.ManageSessions)
        if (caps.runs && caps.sessions) add(AppFeature.SendMessages)
        // 模型、技能、任务：后续阶段接入
    }

    /** 网络恢复了（Android 上由系统网络回调触发）：正在等的重连立刻重试，连接失败的重新连。 */
    fun onNetworkAvailable() {
        // 换了网络，旧的 SSH 连接多半已经死了，但要等心跳超时才发现；直接丢掉，下一个请求重新连
        tunnel?.reset()
        networkKick.tryEmit(Unit)
        if (_connectionState.value is ConnectionState.Failed) _activeConnectionId.value?.let(::connect)
    }

    private val networkKick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 等一会儿再重试；网络恢复时提前结束。 */
    private suspend fun pause(millis: Long) {
        withTimeoutOrNull(millis) { networkKick.first() }
    }

    override fun saveConnection(profile: ConnectionProfile, apiKey: String?, sshSecret: String?) {
        val previous = _connections.value.firstOrNull { it.id == profile.id }
        // 改了 SSH 地址就是另一台服务器了，旧指纹作废
        val moved = previous != null && (previous.host != profile.host || previous.sshPort != profile.sshPort)
        val pinned = when {
            profile.type != ConnectionType.Ssh -> null
            moved -> seenHostKeys[profile.id]
            else -> profile.sshHostKey ?: seenHostKeys[profile.id]
        }
        val saved = profile.copy(sshHostKey = pinned)
        _connections.update { list ->
            if (list.any { it.id == saved.id }) list.map { if (it.id == saved.id) saved else it } else list + saved
        }
        persist { s ->
            var keys = s.keys
            if (apiKey != null) keys = keys + (saved.id to secrets.encrypt(apiKey.trim()))
            if (sshSecret != null) keys = keys + (SshSecretPrefix + saved.id to secrets.encrypt(sshSecret))
            if (saved.type != ConnectionType.Ssh) keys = keys - (SshSecretPrefix + saved.id)
            s.copy(connections = _connections.value, keys = keys, activeConnectionId = s.activeConnectionId ?: saved.id)
        }
        if (_activeConnectionId.value == null || _activeConnectionId.value == saved.id) {
            switchConnection(saved.id)
        }
    }

    override fun sshPublicKey(connectionId: String): String? {
        val profile = _connections.value.firstOrNull { it.id == connectionId } ?: return null
        if (profile.type != ConnectionType.Ssh || profile.sshAuth != SshAuthMethod.Key) return null
        return sshSecretOf(connectionId)?.let { runCatching { SshKeys.publicKeyLine(it) }.getOrNull() }
    }

    override fun deleteConnection(id: String) {
        _connections.update { list -> list.filterNot { it.id == id } }
        persist { s ->
            s.copy(connections = _connections.value, keys = s.keys - id - (SshSecretPrefix + id), runs = s.runs.filterNot { it.connectionId == id })
        }
        if (_activeConnectionId.value == id) {
            val next = _connections.value.firstOrNull()?.id
            _activeConnectionId.value = next
            persist { it.copy(activeConnectionId = next) }
            if (next != null) connect(next) else {
                connectJob?.cancel()
                runScope.cancel()
                closeTunnel()
                client = null
                connectedId = null
                _sessions.value = emptyList()
                _activeRuns.value = emptySet()
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    override fun testConnection(profile: ConnectionProfile, apiKey: String?, sshSecret: String?): Flow<List<ConnectionTestStep>> = flow {
        val key = apiKey?.takeIf { it.isNotBlank() } ?: keyOf(profile.id)
        val ssh = profile.type == ConnectionType.Ssh
        val titles = (if (ssh) listOf("SSH 连接", "SSH 登录") else emptyList()) + listOf("网络可达", "识别 Hermes", "API Key 鉴权", "能力探测")
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

        // SSH：先建隧道，后面几步都走隧道
        var testTunnel: SshTunnel? = null
        val base = if (ssh) 2 else 0
        try {
            val client = if (ssh) {
                mark(0, TestStepStatus.Running)
                val secret = sshSecret?.takeIf { it.isNotEmpty() } ?: sshSecretOf(profile.id)
                val auth = sshAuthOf(profile, secret)
                if (profile.host.isBlank() || profile.sshUser.isNullOrBlank()) {
                    mark(0, TestStepStatus.Failed, "还没填 SSH 主机或用户名")
                    return@flow
                }
                if (auth == null) {
                    mark(0, TestStepStatus.Failed, if (profile.sshAuth == SshAuthMethod.Key) "还没有私钥：导入或生成一把" else "还没填 SSH 密码")
                    return@flow
                }
                val pinned = profile.sshHostKey
                var seen: String? = null
                val t = try {
                    withContext(Dispatchers.IO) {
                        SshTunnel(profile.host.trim(), profile.sshPort, profile.sshUser.trim(), auth, profile.port, pinned, { seen = it })
                            .also { testTunnel = it }
                            .also { it.connect() }
                    }
                } catch (e: SshException) {
                    if (e.stage == SshException.Stage.Login) {
                        mark(0, TestStepStatus.Passed, hostKeyDetail(pinned, testTunnel?.hostKey))
                        mark(1, TestStepStatus.Failed, e.message)
                    } else {
                        mark(0, TestStepStatus.Failed, e.message)
                    }
                    return@flow
                }
                seen?.let { seenHostKeys[profile.id] = it }
                mark(0, TestStepStatus.Passed, hostKeyDetail(pinned, t.hostKey))
                mark(1, TestStepStatus.Passed, "用户 ${profile.sshUser.trim()}")
                clientFactory("http://127.0.0.1:${t.localPort}", key, profile.profile)
            } else {
                try {
                    clientFor(profile, key)
                } catch (e: HermesError) {
                    mark(0, TestStepStatus.Failed, e.message)
                    return@flow
                }
            }

            mark(base, TestStepStatus.Running)
            val started = clock()
            val health = try {
                client.health()
            } catch (e: HermesError.NotHermes) {
                mark(base, TestStepStatus.Passed, "${clock() - started} ms")
                mark(base + 1, TestStepStatus.Failed, e.message)
                return@flow
            } catch (e: HermesError.NotSupported) {
                mark(base, TestStepStatus.Passed, "${clock() - started} ms")
                mark(base + 1, TestStepStatus.Failed, "没有 /health 接口：不是 Hermes，或者版本太旧还没有 API Server")
                return@flow
            } catch (e: HermesError) {
                mark(base, TestStepStatus.Failed, if (ssh) "SSH 已登录，但服务器上 127.0.0.1:${profile.port} 没有响应：确认 Hermes gateway 在运行、API Server 已开启、端口正确" else e.message)
                return@flow
            }
            mark(base, TestStepStatus.Passed, "${clock() - started} ms")

            mark(base + 1, TestStepStatus.Running)
            if (health.platform != null && health.platform != "hermes-agent") {
                mark(base + 1, TestStepStatus.Failed, "对方自称 ${health.platform}，不是 Hermes")
                return@flow
            }
            mark(base + 1, TestStepStatus.Passed, health.knownVersion()?.let { "版本 $it" } ?: "版本未知（可能是源码安装）")

            mark(base + 2, TestStepStatus.Running)
            if (key.isNullOrBlank()) {
                mark(base + 2, TestStepStatus.Failed, "还没填 API Key（服务器上的 API_SERVER_KEY）")
                return@flow
            }
            val caps = try {
                client.probeCapabilities(health.version)
            } catch (e: HermesError) {
                mark(base + 2, TestStepStatus.Failed, e.message)
                return@flow
            }
            mark(base + 2, TestStepStatus.Passed)

            mark(base + 3, TestStepStatus.Running)
            if (!caps.sessions) {
                mark(base + 3, TestStepStatus.Failed, "这台 Hermes 太旧，没有会话接口，建议升级（${caps.summary()}）")
            } else {
                mark(base + 3, TestStepStatus.Passed, caps.summary())
            }
        } finally {
            testTunnel?.let { t -> withContext(Dispatchers.IO) { t.close() } }
        }
    }

    private fun hostKeyDetail(pinned: String?, seen: String?): String = when {
        seen == null -> "已连接"
        pinned == null -> "首次连接，主机指纹 $seen（保存后记住，以后变了会拒绝连接）"
        else -> "主机指纹与记录一致"
    }

    private fun sshAuthOf(profile: ConnectionProfile, secret: String?): SshAuth? {
        if (secret.isNullOrEmpty()) return null
        return if (profile.sshAuth == SshAuthMethod.Key) SshAuth.Key(secret) else SshAuth.Password(secret)
    }

    /** 建立到这个连接的客户端；SSH 连接先建隧道（[onHostKey]：第一次见到的主机指纹）。 */
    private suspend fun openClient(profile: ConnectionProfile, key: String?, sshSecret: String?, onHostKey: (String) -> Unit): HermesClient {
        if (profile.type != ConnectionType.Ssh) return clientFor(profile, key)
        val auth = sshAuthOf(profile, sshSecret) ?: throw HermesError.Ssh("这个 SSH 连接还没有保存私钥或密码")
        val user = profile.sshUser?.trim()?.takeIf { it.isNotEmpty() } ?: throw HermesError.Ssh("这个 SSH 连接还没填用户名")
        val t = try {
            withContext(Dispatchers.IO) {
                SshTunnel(profile.host.trim(), profile.sshPort, user, auth, profile.port, profile.sshHostKey, onHostKey).also {
                    try {
                        it.connect()
                    } catch (e: SshException) {
                        it.close()
                        throw e
                    }
                }
            }
        } catch (e: SshException) {
            throw HermesError.Ssh(e.message ?: "SSH 连接失败", hostKeyChanged = e.stage == SshException.Stage.HostKey)
        }
        tunnel = t
        return clientFactory("http://127.0.0.1:${t.localPort}", key, profile.profile)
    }

    private fun clientFor(profile: ConnectionProfile, key: String?): HermesClient {
        val url = when (profile.type) {
            ConnectionType.Direct -> "http://${profile.host.trim()}:${profile.port}"
            ConnectionType.Local -> "http://127.0.0.1:${profile.port}"
            ConnectionType.Https -> profile.host.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
            ConnectionType.Ssh -> error("SSH 连接走 openClient")
        }
        return clientFactory(url, key, profile.profile)
    }

    // ---- 会话 ----

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    override val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    /**
     * 新建的会话先是本地草稿（id 以 [DraftPrefix] 开头），发第一条消息时才在服务器上创建，
     * 之后草稿 id 通过 [aliases] 指向服务器 id。界面一直拿着草稿 id 也没关系。
     */
    private val drafts = MutableStateFlow<Map<String, Session>>(emptyMap())
    private val aliases = MutableStateFlow<Map<String, String>>(emptyMap())

    private fun resolve(id: String): String = aliases.value[id] ?: id

    /** 本机刚建出来的会话 → 建好的时间。见 [loadSessions]。 */
    private val createdAt = ConcurrentHashMap<String, Long>()

    override fun session(id: String): Flow<Session?> = combine(_sessions, drafts, aliases) { list, draftMap, aliasMap ->
        val real = aliasMap[id] ?: id
        list.firstOrNull { it.id == real } ?: draftMap[id]
    }

    private suspend fun loadSessions() {
        val c = client ?: return
        if (capabilities?.sessions == false) {
            _sessions.value = emptyList()
            return
        }
        try {
            val started = clock()
            val fetched = c.sessions(limit = 100).data
                .filter { it.visibleInList() }
                .map { it.toSession(started) }
            _sessions.update { current ->
                val before = current.associateBy { it.id }
                // 列表请求发出之后才在本机新建/分叉的会话，这次结果里还没有，不能被冲掉
                val newer = current.filter { s -> (createdAt[s.id] ?: Long.MIN_VALUE) >= started && fetched.none { it.id == s.id } }
                newer + fetched.map { s ->
                    s.copy(
                        running = runs.containsKey(s.id),
                        // 服务器不告诉我们哪些会话不能继续，只能记住 409 过的
                        readOnly = before[s.id]?.readOnly == true,
                    )
                }
            }
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

    override fun renameSession(id: String, title: String) {
        val real = resolve(id)
        if (real.startsWith(DraftPrefix)) {
            drafts.update { m -> m[real]?.let { m + (real to it.copy(title = title)) } ?: m }
            return
        }
        optimistic(real, { it.copy(title = title) }) { patchSession(real, HermesClient.patch { put("title", title) }) }
    }

    override fun togglePin(id: String) {
        val real = resolve(id)
        val pinned = _sessions.value.firstOrNull { it.id == real }?.pinned ?: return
        optimistic(real, { it.copy(pinned = !pinned) }) { patchSession(real, HermesClient.patch { put("pinned", !pinned) }) }
    }

    override fun deleteSession(id: String) {
        val real = resolve(id)
        if (real.startsWith(DraftPrefix)) {
            drafts.update { it - real }
            return
        }
        val before = _sessions.value
        _sessions.update { list -> list.filterNot { it.id == real } }
        scope.launch {
            try {
                client?.deleteSession(real) ?: throw HermesError.Unreachable("未连接")
                chats.update { it - real }
            } catch (e: HermesError) {
                _sessions.value = before
                _errors.tryEmit(e.message ?: "删除失败")
            }
        }
    }

    override fun createSession(): String? {
        if (AppFeature.SendMessages !in _features.value || capabilities?.sessionCreate == false) {
            _errors.tryEmit(if (client == null) "还没连上 Hermes" else "这台 Hermes 的版本不支持从手机新建会话")
            return null
        }
        val id = DraftPrefix + UUID.randomUUID()
        drafts.update { it + (id to Session(id = id, title = "新对话", preview = "", updatedAt = clock())) }
        return id
    }

    /** 草稿在服务器上还不存在：现在创建，并把草稿上的本地消息搬过去。 */
    private suspend fun ensureServerSession(uiId: String): String {
        aliases.value[uiId]?.let { return it }
        if (!uiId.startsWith(DraftPrefix)) return uiId
        val c = client ?: throw HermesError.Unreachable("未连接")
        val draft = drafts.value[uiId]
        val created = withRetry { c.createSession(draft?.title?.takeIf { it != "新对话" }) } ?: throw HermesError.NotHermes()
        val session = created.toSession(clock()).copy(running = true)
        createdAt[created.id] = clock()
        _sessions.update { list -> listOf(session) + list.filterNot { it.id == created.id } }
        // 先把聊天状态复制到新 id，再切别名，最后删草稿：界面任何时刻都能看到消息
        chats.update { m -> m[uiId]?.let { m + (created.id to it) } ?: m }
        aliases.update { it + (uiId to created.id) }
        chats.update { it - uiId }
        drafts.update { it - uiId }
        return created.id
    }

    override fun forkSession(id: String, fromItemId: String?): String? {
        val c = client
        if (c == null || capabilities?.sessionFork != true) {
            _errors.tryEmit(if (c == null) "还没连上 Hermes" else "这台 Hermes 的版本不支持分叉会话")
            return null
        }
        val source = resolve(id)
        val title = _sessions.value.firstOrNull { it.id == source }?.title ?: "会话"
        val placeholder = DraftPrefix + UUID.randomUUID()
        drafts.update { it + (placeholder to Session(placeholder, "$title（分叉）", "正在分叉…", clock())) }
        if (fromItemId != null) _errors.tryEmit("Hermes 只能分叉整个会话，已从最新一条开始分叉")
        runScope.launch {
            try {
                val forked = c.forkSession(source) ?: throw HermesError.NotHermes()
                createdAt[forked.id] = clock()
                _sessions.update { list -> listOf(forked.toSession(clock())) + list.filterNot { it.id == forked.id } }
                aliases.update { it + (placeholder to forked.id) }
                drafts.update { it - placeholder }
                loadMessages(forked.id)
            } catch (e: HermesError) {
                drafts.update { m -> m[placeholder]?.let { m + (placeholder to it.copy(preview = "分叉失败")) } ?: m }
                _errors.tryEmit("分叉失败：${e.message}")
            }
        }
        return placeholder
    }

    // ---- 聊天 ----

    /**
     * 本地的聊天条目：还没被服务器记录确认的用户消息、刚收到的回复、审批/追问卡片。
     * 卡片不在服务器的聊天记录里，用 [anchor]（它前面有几条消息）决定插在哪。
     */
    private data class LocalEntry(val item: ChatItem, val anchor: Int, val runId: String? = null)

    private data class ChatState(
        val server: List<ChatItem.Message> = emptyList(),
        val local: List<LocalEntry> = emptyList(),
        val phase: RunPhase = RunPhase.Idle,
        val streaming: String? = null,
        /** 服务器记录拉过了。卡片的位置靠消息数量定，所以加卡片前必须先拉。 */
        val loaded: Boolean = false,
    ) {
        val messageCount: Int get() = server.size + local.count { it.item is ChatItem.Message }

        fun display(): List<ChatItem> {
            val messages = server + local.mapNotNull { it.item as? ChatItem.Message }
            val cards = local.filter { it.item !is ChatItem.Message }
            if (cards.isEmpty()) return messages
            val out = ArrayList<ChatItem>(messages.size + cards.size)
            messages.forEachIndexed { i, m ->
                cards.forEach { if (it.anchor == i) out += it.item }
                out += m
            }
            cards.forEach { if (it.anchor >= messages.size) out += it.item }
            return out
        }

        fun pendingCards(runId: String): Boolean = local.any { e ->
            e.runId == runId && when (val item = e.item) {
                is ChatItem.Approval -> item.resolvedChoice == null
                is ChatItem.Clarify -> item.answer == null
                else -> false
            }
        }
    }

    /** 服务器会话 id（草稿发出前是草稿 id）→ 聊天状态。 */
    private val chats = MutableStateFlow<Map<String, ChatState>>(emptyMap())

    private fun updateChat(sessionId: String, transform: (ChatState) -> ChatState) =
        chats.update { it + (sessionId to transform(it[sessionId] ?: ChatState())) }

    private fun chatOf(uiId: String): Flow<ChatState?> = combine(chats, aliases) { map, aliasMap -> map[aliasMap[uiId] ?: uiId] }

    override fun chatItems(sessionId: String): Flow<List<ChatItem>> =
        chatOf(sessionId).map { it?.display().orEmpty() }.onStart {
            scope.launch { resolve(sessionId).takeUnless { it.startsWith(DraftPrefix) }?.let { loadMessages(it) } }
        }

    override fun runPhase(sessionId: String): Flow<RunPhase> = chatOf(sessionId).map { it?.phase ?: RunPhase.Idle }

    override fun streamingText(sessionId: String): Flow<String?> = chatOf(sessionId).map { st ->
        st?.streaming?.takeIf { it.isNotBlank() && _settings.value.streamReplies }
    }

    /** 拉服务器上的聊天记录，和本地还没确认的条目合并。 */
    private suspend fun loadMessages(sessionId: String) {
        val c = client ?: return
        if (capabilities?.sessionMessages == false) return
        try {
            val now = clock()
            val items = c.messages(sessionId).data.mapIndexedNotNull { i, m -> m.toChatItem(i, now, ::cachedImage) }.linkQuotes()
            val running = runs.containsKey(sessionId)
            updateChat(sessionId) { st -> st.copy(server = items, local = st.local.reconcile(items, running), loaded = true) }
        } catch (e: HermesError) {
            _errors.tryEmit(e.message ?: "读取聊天记录失败")
        }
    }

    /**
     * 服务器记录到了的本地消息就去掉，避免出现两份。
     * 卡片、没发出去的消息一直保留；这一轮结束了（[running] 为 false）就完全以服务器为准。
     */
    private fun List<LocalEntry>.reconcile(server: List<ChatItem.Message>, running: Boolean): List<LocalEntry> {
        val tail = server.takeLast(8)
        return filter { entry ->
            val m = entry.item as? ChatItem.Message ?: return@filter true
            when {
                m.status != DeliveryStatus.Sent -> true
                tail.any { it.role == m.role && it.text.trim() == m.text.trim() } -> false
                else -> running
            }
        }
    }

    /** 已提交、还在等结果的一轮对话。 */
    private class ActiveRun(val runId: String, val sessionId: String, var lastSeq: Long = -1)

    /** 会话 id → 正在进行的一轮。 */
    private val runs = ConcurrentHashMap<String, ActiveRun>()
    private val _activeRuns = MutableStateFlow<Set<String>>(emptySet())
    override val activeRuns: StateFlow<Set<String>> = _activeRuns.asStateFlow()

    /** 卡片 id → (run id, 回复时要带的信息)。 */
    private val controls = ConcurrentHashMap<String, Pair<String, ControlRequest>>()

    /** 还没被服务器接受的消息：本地消息 id → (界面上的会话 id, 发出去的全文)。重发时用同一个 id 做幂等键。 */
    private val outbox = ConcurrentHashMap<String, Outgoing>()

    /** 一条待发送的消息。附件在真正提交时才变成请求内容（图片转 data URL、文件上传），重试时再来一遍。 */
    private data class Outgoing(val uiId: String, val text: String, val attachments: List<Attachment>, val quote: Quote?)

    /** 本机缓存里的图片（按 SHA-256 找）。 */
    private fun cachedImage(sha256: String): String? {
        val dir = mediaDir ?: return null
        return ImageExtensions.asSequence().map { File(dir, "$sha256.$it") }.firstOrNull { it.isFile }?.path
    }

    override fun canSendAnyFile(): Boolean {
        val profile = _connections.value.firstOrNull { it.id == connectedId } ?: return false
        return profile.type == ConnectionType.Ssh || (profile.type == ConnectionType.Local && localFileExporter != null)
    }

    private fun publishRuns() {
        _activeRuns.value = runs.keys.toSet()
        val connection = connectedId ?: return
        persist { s ->
            s.copy(runs = s.runs.filterNot { it.connectionId == connection } +
                runs.values.map { StoredRun(connection, it.sessionId, it.runId) })
        }
    }

    override fun sendMessage(sessionId: String, text: String, attachments: List<Attachment>, quote: Quote?) {
        if (text.isBlank() && attachments.isEmpty()) return
        val localId = "local-" + UUID.randomUUID()
        val key = resolve(sessionId)
        val message = ChatItem.Message(
            localId, clock(), Role.User, text.trim(),
            images = attachments.displayImages(),
            quote = quote,
            status = DeliveryStatus.Sending,
            files = attachments.displayFiles(),
        )
        updateChat(key) { it.copy(local = it.local + LocalEntry(message, it.messageCount), phase = RunPhase.Working, streaming = null) }
        outbox[localId] = Outgoing(sessionId, text.trim(), attachments, quote)
        runScope.launch { submit(localId) }
    }

    override fun retryMessage(sessionId: String, itemId: String) {
        val key = resolve(sessionId)
        val message = chats.value[key]?.local?.firstNotNullOfOrNull { (it.item as? ChatItem.Message)?.takeIf { m -> m.id == itemId } } ?: return
        if (message.status != DeliveryStatus.Failed || runs.containsKey(key)) return
        // App 重启过的话附件已经丢了，只能重发文字
        outbox.putIfAbsent(itemId, Outgoing(sessionId, message.text, emptyList(), message.quote))
        setLocalStatus(key, itemId, DeliveryStatus.Sending)
        updateChat(key) { it.copy(phase = RunPhase.Working) }
        runScope.launch { submit(itemId) }
    }

    private suspend fun submit(localId: String) {
        val out = outbox[localId] ?: return
        val uiId = out.uiId
        try {
            val c = client ?: throw HermesError.Unreachable("还没连上 Hermes")
            val sessionId = ensureServerSession(uiId)
            val input = buildInput(out)
            val accepted = withRetry { c.startRun(sessionId, input, idempotencyKey = localId) }
            val runId = accepted.run_id ?: throw HermesError.NotHermes()
            outbox.remove(localId)
            setLocalStatus(sessionId, localId, DeliveryStatus.Sent)
            val preview = out.text.lineSequence().lastOrNull { it.isNotBlank() }?.take(80)
                ?: if (out.attachments.imageCount() > 0) "[图片]" else "[文件]"
            updateSession(sessionId) { it.copy(preview = preview, updatedAt = clock()) }
            track(sessionId, runId, fromSeq = -1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HermesError) {
            val key = resolve(uiId)
            setLocalStatus(key, localId, DeliveryStatus.Failed)
            updateChat(key) { it.copy(phase = RunPhase.Idle) }
            _errors.tryEmit(sendErrorText(key, e))
        }
    }

    /**
     * 消息 + 附件 → `/v1/runs` 的 input。只有文字时是字符串；有图片时是
     * `[{"role":"user","content":[{"type":"text",...},{"type":"image_url",...}]}]`。
     */
    private suspend fun buildInput(out: Outgoing): JsonElement = withContext(Dispatchers.IO) {
        val blocks = mutableListOf<String>()
        val images = mutableListOf<Attachment.Image>()
        for (attachment in out.attachments) {
            when (attachment) {
                is Attachment.Image -> images += attachment
                is Attachment.Pdf -> {
                    images += attachment.pages
                    blocks += AttachmentText.pdf(attachment.name, attachment.totalPages, attachment.pages.size)
                }
                is Attachment.TextFile -> blocks += AttachmentText.textFile(attachment.name, attachment.text)
                is Attachment.BinaryFile -> blocks += AttachmentText.uploaded(attachment.name, deliverFile(attachment))
            }
        }
        if (images.size > MaxImagesPerMessage) throw HermesError.BadUrl("一条消息最多 $MaxImagesPerMessage 张图片（PDF 每页算一张）")
        val text = (listOf(quotedInput(out.quote, out.text)) + blocks).filter { it.isNotBlank() }.joinToString("\n\n")
        if (images.isEmpty()) return@withContext JsonPrimitive(text)
        buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    if (text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", text) })
                    images.forEach { image ->
                        val file = File(image.path)
                        if (!file.isFile) throw HermesError.BadUrl("图片文件不见了，请重新选择")
                        val url = "data:${mimeOf(file.name)};base64," + Base64.getEncoder().encodeToString(file.readBytes())
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", url) })
                        })
                    }
                })
            })
        }
    }

    /** 普通文件交给 Hermes：SSH 上传到服务器，本机 Termux 存到下载目录。返回 Hermes 那边能读到的路径。 */
    private fun deliverFile(file: Attachment.BinaryFile): String {
        val profile = _connections.value.firstOrNull { it.id == connectedId }
        return when (profile?.type) {
            ConnectionType.Ssh -> {
                val t = tunnel ?: throw HermesError.Ssh("SSH 隧道没连上")
                try {
                    t.upload(file.path, file.name)
                } catch (e: Exception) {
                    throw HermesError.Ssh("文件上传失败：${e.message}")
                }
            }
            ConnectionType.Local -> localFileExporter?.invoke(file.path, file.name, file.mime)
                ?: throw HermesError.NotSupported("把文件交给本机 Termux")
            else -> throw HermesError.NotSupported("发送这种文件（直连和 HTTPS 只能发图片、PDF 和文本文件，其它文件请用 SSH 连接）")
        }
    }

    private fun sendErrorText(sessionId: String, e: HermesError): String = when {
        e is HermesError.Conflict && e.code == "permission_denied" -> {
            updateSession(sessionId) { it.copy(readOnly = true) }
            "这个会话不能从手机继续，可以分叉一份再聊"
        }
        e is HermesError.Conflict && e.code == "session_active_turn" -> "这个会话正在别处回复中，等它结束再发"
        e is HermesError.Server && e.status == 429 -> "Hermes 同时处理的任务太多了，稍后点消息重发"
        else -> "发送失败：${e.message}"
    }

    private fun setLocalStatus(sessionId: String, itemId: String, status: DeliveryStatus) = updateChat(sessionId) { st ->
        st.copy(local = st.local.map { e ->
            val m = e.item as? ChatItem.Message
            if (m?.id == itemId) e.copy(item = m.copy(status = status)) else e
        })
    }

    /** 网络错误和“服务器忙”（429/503）重试几次，其它错误直接抛出。幂等键保证重试不会重复执行。 */
    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: HermesError) {
                attempt++
                val wait = when {
                    attempt >= 4 -> throw e
                    e is HermesError.Unreachable || e is HermesError.Timeout -> backoff(attempt)
                    e is HermesError.Server && (e.status == 429 || e.status == 503 || e.status == 502) ->
                        e.retryAfterSeconds?.times(1000)?.coerceAtMost(30_000) ?: backoff(attempt)
                    else -> throw e
                }
                pause(wait)
            }
        }
    }

    private fun track(sessionId: String, runId: String, fromSeq: Long) {
        val run = ActiveRun(runId, sessionId, fromSeq)
        runs[sessionId] = run
        publishRuns()
        updateSession(sessionId) { it.copy(running = true) }
        updateChat(sessionId) { it.copy(phase = if (it.pendingCards(runId)) RunPhase.AwaitingInput else RunPhase.Working) }
        runScope.launch { follow(run) }
    }

    /** App 被杀之前还没等到结果的轮次：重新订阅（服务器会从头补发事件），或者直接拿最终结果。 */
    private fun resumeStoredRuns(connectionId: String) {
        state.value.runs.filter { it.connectionId == connectionId }.forEach { stored ->
            if (!runs.containsKey(stored.sessionId)) track(stored.sessionId, stored.runId, fromSeq = -1)
        }
    }

    /**
     * 跟着一轮对话直到结束。事件流断了就等一会儿重连（带上最后的序号，服务器补发漏掉的事件）；
     * 重连之前先查一下状态，可能已经结束了。
     */
    private suspend fun follow(run: ActiveRun) {
        if (chats.value[run.sessionId]?.loaded != true) loadMessages(run.sessionId)
        var failures = 0
        while (runs[run.sessionId] === run) {
            val c = client ?: return
            try {
                val terminal = c.runEvents(run.runId, run.lastSeq).firstOrNull { frame ->
                    val event = parseRunEvent(frame)
                    val seq = event.seq
                    if (seq != null && seq <= run.lastSeq) return@firstOrNull false
                    if (seq != null) run.lastSeq = seq
                    failures = 0
                    handle(run, event)
                }
                if (terminal != null) return
            } catch (e: CancellationException) {
                throw e
            } catch (e: HermesError.NotSupported) {
                // 服务器不认识这个任务了（重启过，或者早就结束、记录已清理）
                if (!settleFromStatus(run, c)) end(run, error = null)
                return
            } catch (e: HermesError) {
                failures++
            }
            // 事件流结束了却没有结束事件，或者网络断了：问一下状态
            if (settleFromStatus(run, c)) return
            pause(backoff(failures.coerceAtLeast(1)))
        }
    }

    /** 查状态；已结束就收尾并返回 true，还在进行就补上等待中的审批/追问。 */
    private suspend fun settleFromStatus(run: ActiveRun, c: HermesClient): Boolean {
        val status = try {
            c.runStatus(run.runId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HermesError.NotSupported) {
            end(run, error = null)
            return true
        } catch (e: HermesError) {
            return false
        }
        when (status.status) {
            "completed" -> complete(run, status.output.orEmpty())
            "failed" -> end(run, status.error ?: "Hermes 处理失败")
            "cancelled", "interrupted" -> end(run, error = null)
            "unknown" -> end(run, "Hermes 不确定这一轮有没有完成，请在电脑上确认")
            else -> {
                status.pending_controls.orEmpty().forEach { obj ->
                    ControlRequest.from(obj, ControlRequest.Kind.Approval, status.execution_generation)?.let { addControl(run, it, seq = null) }
                }
                status.approval?.let { obj -> ControlRequest.from(obj, ControlRequest.Kind.Approval)?.let { addControl(run, it, seq = null) } }
                return false
            }
        }
        return true
    }

    /** 处理一个事件；返回 true 表示这一轮结束了。 */
    private fun handle(run: ActiveRun, event: RunEvent): Boolean {
        when (event) {
            is RunEvent.Delta -> if (_settings.value.streamReplies) {
                updateChat(run.sessionId) { it.copy(streaming = ((it.streaming ?: "") + event.text).trimStart()) }
            }
            // 工具调用开始/结束：之前那段只是过程中的话，不是最终回复
            is RunEvent.Step -> updateChat(run.sessionId) { if (it.streaming != null) it.copy(streaming = null) else it }
            is RunEvent.Control -> addControl(run, event.request, event.seq)
            is RunEvent.ControlSettled -> settleControl(run, event.promptId, event.choice)
            is RunEvent.Completed -> complete(run, event.output)
            is RunEvent.Failed -> end(run, event.error)
            is RunEvent.Cancelled -> end(run, error = null)
            is RunEvent.Truncated, is RunEvent.Ignored -> Unit
        }
        return event is RunEvent.Completed || event is RunEvent.Failed || event is RunEvent.Cancelled
    }

    private fun addControl(run: ActiveRun, request: ControlRequest, seq: Long?) {
        val itemId = "ctl-${run.runId}-${request.promptId ?: seq ?: UUID.randomUUID()}"
        if (controls.containsKey(itemId)) return
        val exists = chats.value[run.sessionId]?.local?.any { it.item.id == itemId } == true
        if (exists) return
        controls[itemId] = run.runId to request
        val item: ChatItem = when (request.kind) {
            ControlRequest.Kind.Approval -> ChatItem.Approval(
                id = itemId,
                time = clock(),
                summary = request.description.ifBlank { "执行一条命令" },
                command = request.command,
                // 不同版本给的顺序不一样，统一成：允许一次、本会话、始终、拒绝
                choices = request.choices.ifEmpty { listOf("once", "deny") }
                    .sortedBy { ApprovalOrder.indexOf(it).let { i -> if (i < 0) ApprovalOrder.size else i } },
            )
            ControlRequest.Kind.Clarify -> ChatItem.Clarify(
                id = itemId,
                time = clock(),
                question = request.question.ifBlank { request.description },
                options = request.choices,
            )
        }
        updateChat(run.sessionId) {
            it.copy(local = it.local + LocalEntry(item, it.messageCount, run.runId), phase = RunPhase.AwaitingInput, streaming = null)
        }
        notify(run.sessionId, ChatNotice.Kind.NeedsInput,
            if (request.kind == ControlRequest.Kind.Approval) "需要你批准：${(item as ChatItem.Approval).summary}" else "Hermes 在问你：${(item as ChatItem.Clarify).question}")
    }

    /** 审批/追问在别处（或者刚才在这里）处理掉了。 */
    private fun settleControl(run: ActiveRun, promptId: String?, choice: String?) {
        val itemId = controls.entries.firstOrNull { (_, v) -> v.first == run.runId && (promptId == null || v.second.promptId == promptId) }?.key
            ?: promptId?.let { "ctl-${run.runId}-$it" }
            ?: return
        controls.remove(itemId)
        updateChat(run.sessionId) { st ->
            val local = st.local.map { e ->
                if (e.item.id != itemId) return@map e
                when (val item = e.item) {
                    is ChatItem.Approval -> if (item.resolvedChoice == null) e.copy(item = item.copy(resolvedChoice = choice.orEmpty())) else e
                    is ChatItem.Clarify -> if (item.answer == null) e.copy(item = item.copy(answer = choice ?: "已在其他设备回答")) else e
                    else -> e
                }
            }
            val next = st.copy(local = local)
            next.copy(phase = if (next.pendingCards(run.runId)) RunPhase.AwaitingInput else RunPhase.Working)
        }
    }

    private fun finish(run: ActiveRun) {
        if (!runs.remove(run.sessionId, run)) return
        publishRuns()
        updateSession(run.sessionId) { it.copy(running = false) }
        controls.entries.removeIf { it.value.first == run.runId }
        // 这一轮结束时还没处理的卡片已经失效
        updateChat(run.sessionId) { st ->
            st.copy(local = st.local.map { e ->
                if (e.runId != run.runId) return@map e
                when (val item = e.item) {
                    is ChatItem.Approval -> if (item.resolvedChoice == null) e.copy(item = item.copy(resolvedChoice = "")) else e
                    is ChatItem.Clarify -> if (item.answer == null) e.copy(item = item.copy(answer = "（已失效）")) else e
                    else -> e
                }
            })
        }
    }

    private fun complete(run: ActiveRun, output: String) {
        if (runs[run.sessionId] !== run) return
        val text = output.trim().ifEmpty { chats.value[run.sessionId]?.streaming?.trim().orEmpty() }
        finish(run)
        // 先把结果显示出来，再去服务器拉正式记录对齐
        updateChat(run.sessionId) { st ->
            val reply = text.takeIf { it.isNotEmpty() }?.let {
                LocalEntry(ChatItem.Message("reply-${run.runId}", clock(), Role.Assistant, it), st.messageCount, run.runId)
            }
            st.copy(phase = RunPhase.Idle, streaming = null, local = st.local + listOfNotNull(reply))
        }
        updateSession(run.sessionId) { it.copy(preview = text.replace('\n', ' ').take(80), updatedAt = clock()) }
        if (text.isNotEmpty()) notify(run.sessionId, ChatNotice.Kind.Reply, text)
        scope.launch { reconcileAfterRun(run.sessionId, text) }
    }

    private fun end(run: ActiveRun, error: String?) {
        if (runs[run.sessionId] !== run) return
        finish(run)
        updateChat(run.sessionId) { it.copy(phase = RunPhase.Idle, streaming = null) }
        if (error != null) {
            _errors.tryEmit("Hermes 出错了：$error")
            notify(run.sessionId, ChatNotice.Kind.Failed, error)
        }
        scope.launch { reconcileAfterRun(run.sessionId, expectedReply = null) }
    }

    /** 一轮结束后以服务器记录为准。服务器写入记录可能比结束事件晚一点，所以没看到回复就稍等再拉。 */
    private suspend fun reconcileAfterRun(sessionId: String, expectedReply: String?) {
        repeat(3) { attempt ->
            loadMessages(sessionId)
            val server = chats.value[sessionId]?.server.orEmpty()
            val seen = expectedReply.isNullOrEmpty() || server.takeLast(4).any { it.role == Role.Assistant && it.text.trim() == expectedReply }
            if (seen) return
            if (attempt < 2) delay(800)
        }
    }

    override fun resolveApproval(sessionId: String, itemId: String, choice: String) {
        val key = resolve(sessionId)
        val (runId, request) = controls[itemId] ?: return
        setCard(key, itemId, runId) { if (it is ChatItem.Approval) it.copy(resolvedChoice = choice) else it }
        runScope.launch {
            try {
                withRetry { client?.respondApproval(runId, request.approvalBody(choice)) ?: throw HermesError.Unreachable("未连接") }
                controls.remove(itemId)
            } catch (e: HermesError.Conflict) {
                // 已经在别处处理过了
                controls.remove(itemId)
            } catch (e: HermesError) {
                setCard(key, itemId, runId) { if (it is ChatItem.Approval) it.copy(resolvedChoice = null) else it }
                _errors.tryEmit("审批没发出去：${e.message}")
            }
        }
    }

    override fun answerClarify(sessionId: String, itemId: String, answer: String) {
        val key = resolve(sessionId)
        val (runId, request) = controls[itemId] ?: return
        setCard(key, itemId, runId) { if (it is ChatItem.Clarify) it.copy(answer = answer) else it }
        runScope.launch {
            try {
                withRetry { client?.respondClarify(runId, request.clarifyBody(answer)) ?: throw HermesError.Unreachable("未连接") }
                controls.remove(itemId)
            } catch (e: HermesError.Conflict) {
                controls.remove(itemId)
            } catch (e: HermesError) {
                setCard(key, itemId, runId) { if (it is ChatItem.Clarify) it.copy(answer = null) else it }
                _errors.tryEmit("回答没发出去：${e.message}")
            }
        }
    }

    private fun setCard(sessionId: String, itemId: String, runId: String, transform: (ChatItem) -> ChatItem) = updateChat(sessionId) { st ->
        val next = st.copy(local = st.local.map { if (it.item.id == itemId) it.copy(item = transform(it.item)) else it })
        if (runs[sessionId]?.runId == runId) {
            next.copy(phase = if (next.pendingCards(runId)) RunPhase.AwaitingInput else RunPhase.Working)
        } else {
            next
        }
    }

    override fun stop(sessionId: String) {
        val run = runs[resolve(sessionId)] ?: return
        runScope.launch {
            try {
                withRetry { client?.stopRun(run.runId) ?: throw HermesError.Unreachable("未连接") }
            } catch (e: HermesError.Conflict) {
                // 已经结束了
            } catch (e: HermesError) {
                _errors.tryEmit("停止失败：${e.message}")
            }
        }
    }

    override fun regenerate(sessionId: String, itemId: String) = notYet<Unit>("重新生成")
    override fun deleteItem(sessionId: String, itemId: String) {
        val key = resolve(sessionId)
        // 只有没发出去的消息能删；服务器上的记录 Hermes 不提供单条删除
        val removable = chats.value[key]?.local?.any { (it.item as? ChatItem.Message)?.let { m -> m.id == itemId && m.status == DeliveryStatus.Failed } == true } == true
        if (removable) {
            outbox.remove(itemId)
            updateChat(key) { st -> st.copy(local = st.local.filterNot { it.item.id == itemId }) }
        } else {
            _errors.tryEmit("Hermes 不支持删除单条聊天记录")
        }
    }

    // ---- 通知 ----

    private val _notices = MutableSharedFlow<ChatNotice>(extraBufferCapacity = 16)
    override val notices: Flow<ChatNotice> = _notices

    private fun notify(sessionId: String, kind: ChatNotice.Kind, text: String) {
        val uiId = aliases.value.entries.firstOrNull { it.value == sessionId }?.key ?: sessionId
        val title = _sessions.value.firstOrNull { it.id == sessionId }?.title ?: "Hermes"
        _notices.tryEmit(ChatNotice(uiId, title, text.take(200), kind))
    }

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

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val errors: Flow<String> = _errors

    @Suppress("UNCHECKED_CAST")
    private fun <T> notYet(what: String): T {
        _errors.tryEmit("「$what」会在后续开发阶段接入真实 Hermes")
        return Unit as T
    }

    private companion object {
        const val DraftPrefix = "draft-"
        val ImageExtensions = listOf("jpg", "png", "webp", "gif")

        fun mimeOf(name: String): String = when (name.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
        /** 加密保存 SSH 私钥/密码时用的键：`ssh:<连接 id>`。 */
        const val SshSecretPrefix = "ssh:"
        val ApprovalOrder = listOf("once", "session", "always", "deny")
    }

    // 放在最后：自动连接会用到上面所有状态，必须等它们都初始化完（否则重启 App 时会崩溃）
    init {
        _activeConnectionId.value?.let { connect(it) }
    }
}
