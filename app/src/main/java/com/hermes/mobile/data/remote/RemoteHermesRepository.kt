package com.hermes.mobile.data.remote

import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.model.Attachment
import com.hermes.mobile.data.model.Draft
import com.hermes.mobile.data.model.FileRef
import com.hermes.mobile.data.model.ImageRef
import kotlinx.coroutines.sync.withLock
import com.hermes.mobile.data.model.MaxImagesPerMessage
import com.hermes.mobile.data.model.displayFiles
import com.hermes.mobile.data.model.displayImages
import com.hermes.mobile.data.model.imageCount
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import com.hermes.mobile.data.model.GatewayStatus
import com.hermes.mobile.data.model.SessionGrouping
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import com.hermes.mobile.data.model.ProjectInfo
import java.io.IOException
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
import com.hermes.mobile.data.store.ChatCache
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
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
    /** 会话列表和聊天记录的本机副本放在这里（打开就显示、没网也能看）。为 null 时不缓存。 */
    cacheDir: File? = null,
    /**
     * 本机 Termux 连接发普通文件时，把文件存到 Termux 也能读到的地方（Android 上是“下载/Hermes”），
     * 返回 Termux 里看到的路径；不支持时返回 null。
     */
    private val localFileExporter: ((path: String, name: String, mime: String) -> String?)? = null,
    /** “立即运行”后多久查一次任务状态。测试里调短。 */
    private val jobPollMillis: Long = 5_000,
    /** 断线重连的等待时间（毫秒），第 n 次失败等多久。测试里调短。 */
    private val backoff: (attempt: Int) -> Long = { attempt -> (1000L shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(15_000) },
) : HermesRepository {
    private val cache = cacheDir?.let(::ChatCache)

    /** 这个连接的会话列表已经从服务器读到过（之后才把列表写回缓存，免得用旧数据覆盖）。 */
    @Volatile private var sessionsFresh = false

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

    // ---- 正在用哪个 bot ----

    /** 连接本身设的 Profile（没设就是 "default"）。 */
    private fun baseBot(p: ConnectionProfile): String = p.profile?.trim()?.takeIf { it.isNotEmpty() } ?: "default"
    /** 这个连接上正在用的 bot；没换过就是 null（用连接本身的 Profile）。 */
    private fun botOf(connectionId: String): String? = state.value.activeBots[connectionId]
    private fun botKeyName(connectionId: String, bot: String) = "bot:$connectionId:$bot"
    private fun botKeyOf(connectionId: String, bot: String): String? = state.value.keys[botKeyName(connectionId, bot)]?.let(secrets::decrypt)

    /** 按正在用的 bot 改过的连接设置：Profile 换成 bot（默认 bot 不加 /p/ 前缀）。 */
    private fun effectiveProfile(p: ConnectionProfile): ConnectionProfile {
        val bot = botOf(p.id) ?: return p
        return p.copy(profile = bot.takeIf { it != "default" })
    }

    /** 正在用的 bot 的 key：连接本身的 Profile 用连接的 key，别的 bot 用它自己的。 */
    private fun effectiveKey(p: ConnectionProfile): String? {
        val bot = botOf(p.id) ?: return keyOf(p.id)
        return if (bot == baseBot(p)) keyOf(p.id) else botKeyOf(p.id, bot)
    }

    /** 缓存、没收到的回复、模型偏好都按“连接 + bot”分开存。 */
    private fun scopeOf(connectionId: String): String = botOf(connectionId)?.let { "$connectionId#$it" } ?: connectionId
    private val scopeId: String? get() = connectedId?.let(::scopeOf)
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
        // 先显示上次存下的列表，连上之后再换成服务器的
        sessionsFresh = false
        val scope0 = scopeOf(id)
        _sessions.value = cache?.sessions(scope0).orEmpty()
        _sessionGroups.value = cache?.groups(scope0) ?: SessionGrouping()
        _archivedLocal.value = cache?.archived(scope0).orEmpty()
        _activeBot.value = botOf(id) ?: baseBot(profile)
        if (_bots.value == null) _bots.value = cache?.bots(id)
        chats.value = emptyMap()
        connectJob = scope.launch {
            try {
                val started = clock()
                val newClient = openClient(effectiveProfile(profile), effectiveKey(profile), sshSecretOf(id)) { fingerprint -> pinHostKey(id, fingerprint) }
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
                resumeStoredRuns(scopeOf(id))
                scope.launch { refreshBots() }
                scope.launch { loadModels() }
                scope.launch { loadJobs() }
                scope.launch { refreshCapabilities() }
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
            profile.sshUser.isNullOrBlank() -> null
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
            if (saved.sshUser.isNullOrBlank()) keys = keys - (SshSecretPrefix + saved.id)
            s.copy(connections = _connections.value, keys = keys, activeConnectionId = s.activeConnectionId ?: saved.id)
        }
        if (_activeConnectionId.value == null || _activeConnectionId.value == saved.id) {
            switchConnection(saved.id)
        }
    }

    override fun sshPublicKey(connectionId: String): String? {
        val profile = _connections.value.firstOrNull { it.id == connectionId } ?: return null
        if (profile.sshUser.isNullOrBlank() || profile.sshAuth != SshAuthMethod.Key) return null
        return sshSecretOf(connectionId)?.let { runCatching { SshKeys.publicKeyLine(it) }.getOrNull() }
    }

    override fun deleteConnection(id: String) {
        cache?.removeConnection(id)
        _connections.update { list -> list.filterNot { it.id == id } }
        persist { s ->
            s.copy(
                connections = _connections.value,
                keys = s.keys.filterKeys { k -> k != id && k != SshSecretPrefix + id && !k.startsWith("bot:$id:") },
                runs = s.runs.filterNot { it.connectionId == id || it.connectionId.startsWith("$id#") },
                activeBots = s.activeBots - id,
            )
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
        val fileSsh = hasFileSsh(profile)
        val titles = (if (ssh) listOf("SSH 连接", "SSH 登录") else emptyList()) + listOf("网络可达", "识别 Hermes", "API Key 鉴权", "能力探测") +
            (if (fileSsh) listOf("文件互通（SSH）") else emptyList())
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
                // 先单独试一下转发，能分清“服务器禁止转发”和“端口上没服务”
                withContext(Dispatchers.IO) { t.probeForward() }?.let { reason ->
                    mark(2, TestStepStatus.Failed, reason)
                    return@flow
                }
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
            if (fileSsh) {
                mark(base + 4, TestStepStatus.Running)
                val secret = sshSecret?.takeIf { it.isNotEmpty() } ?: sshSecretOf(profile.id)
                val auth = sshAuthOf(profile, secret)
                if (auth == null) {
                    mark(base + 4, TestStepStatus.Failed, if (profile.sshAuth == SshAuthMethod.Key) "还没有私钥：导入或生成一把" else "还没填 SSH 密码")
                } else {
                    var seen: String? = null
                    try {
                        val t = withContext(Dispatchers.IO) {
                            SshTunnel(fileSshHost(profile), profile.sshPort, profile.sshUser!!.trim(), auth, profile.port, profile.sshHostKey, { seen = it })
                                .also { testTunnel = it }
                                .also { it.connect() }
                        }
                        seen?.let { seenHostKeys[profile.id] = it }
                        mark(base + 4, TestStepStatus.Passed, "可以显示 Hermes 生成的图片、发送任意文件 · " + hostKeyDetail(profile.sshHostKey, t.hostKey))
                    } catch (e: SshException) {
                        mark(base + 4, TestStepStatus.Failed, e.message)
                    }
                }
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
        if (profile.type != ConnectionType.Ssh) {
            val c = clientFor(profile, key)
            if (hasFileSsh(profile)) openFileSsh(profile, sshSecret, onHostKey)
            return c
        }
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

    /** 本机 Termux / 直连也可以配一个 SSH（只用来传文件：显示 Hermes 生成的图片、发任意文件）。 */
    private fun hasFileSsh(profile: ConnectionProfile): Boolean =
        (profile.type == ConnectionType.Local || profile.type == ConnectionType.Direct) && !profile.sshUser.isNullOrBlank()

    private fun fileSshHost(profile: ConnectionProfile): String =
        if (profile.type == ConnectionType.Local) "127.0.0.1" else profile.host.trim()

    /** 尽力而为：连不上不影响聊天，只提示一下。 */
    private suspend fun openFileSsh(profile: ConnectionProfile, sshSecret: String?, onHostKey: (String) -> Unit) {
        val auth = sshAuthOf(profile, sshSecret) ?: return
        try {
            tunnel = withContext(Dispatchers.IO) {
                SshTunnel(fileSshHost(profile), profile.sshPort, profile.sshUser!!.trim(), auth, profile.port, profile.sshHostKey, onHostKey).also {
                    try {
                        it.connect()
                    } catch (e: SshException) {
                        it.close()
                        throw e
                    }
                }
            }
        } catch (e: SshException) {
            _errors.tryEmit("文件互通的 SSH 没连上（聊天不受影响）：${e.message}")
        }
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
    private val draftSessions = MutableStateFlow<Map<String, Session>>(emptyMap())
    private val aliases = MutableStateFlow<Map<String, String>>(emptyMap())

    private fun resolve(id: String): String = aliases.value[id] ?: id

    /** 本机刚建出来的会话 → 建好的时间。见 [loadSessions]。 */
    private val createdAt = ConcurrentHashMap<String, Long>()

    override fun session(id: String): Flow<Session?> = combine(_sessions, draftSessions, aliases) { list, draftMap, aliasMap ->
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
            // 列表里又出现了：在别处（比如桌面版）取消了归档
            val listed = fetched.map { it.id }.toSet()
            if (_archivedLocal.value.any { it.id in listed && it.id !in archivingNow }) {
                _archivedLocal.update { list -> list.filterNot { it.id in listed && it.id !in archivingNow } }
                saveArchived()
            }
            _sessions.update { list -> list.filterNot { it.id in archivingNow } }
            sessionsFresh = true
            saveSessionsToCache()
            scope.launch { loadGroups() }
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
            draftSessions.update { m -> m[real]?.let { m + (real to it.copy(title = title)) } ?: m }
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
            draftSessions.update { it - real }
            return
        }
        scopeId?.let { c -> cache?.removeSession(c, real) }
        val before = _sessions.value
        val archivedBefore = _archivedLocal.value
        _sessions.update { list -> list.filterNot { it.id == real } }
        // 归档里的会话也能删
        _archivedLocal.update { list -> list.filterNot { it.id == real } }
        _sessionGroups.update { g -> g.copy(archived = g.archived.filterNot { it.id == real }) }
        if (archivedBefore.size != _archivedLocal.value.size) saveArchived()
        scope.launch {
            try {
                client?.deleteSession(real) ?: throw HermesError.Unreachable("未连接")
                chats.update { it - real }
            } catch (e: HermesError) {
                _sessions.value = before
                if (archivedBefore.size != _archivedLocal.value.size) {
                    _archivedLocal.value = archivedBefore
                    saveArchived()
                }
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
        val model = appDefaultModel()?.model
        draftSessions.update { it + (id to Session(id = id, title = "新对话", preview = "", updatedAt = clock(), model = model)) }
        return id
    }

    /** 草稿在服务器上还不存在：现在创建，并把草稿上的本地消息搬过去。 */
    private suspend fun ensureServerSession(uiId: String): String {
        aliases.value[uiId]?.let { return it }
        if (!uiId.startsWith(DraftPrefix)) return uiId
        val c = client ?: throw HermesError.Unreachable("未连接")
        val draft = draftSessions.value[uiId]
        val created = withRetry { c.createSession(draft?.title?.takeIf { it != "新对话" }) } ?: throw HermesError.NotHermes()
        val session = created.toSession(clock()).copy(running = true)
        createdAt[created.id] = clock()
        _sessions.update { list -> listOf(session) + list.filterNot { it.id == created.id } }
        // 先把聊天状态复制到新 id，再切别名，最后删草稿：界面任何时刻都能看到消息
        chats.update { m -> m[uiId]?.let { m + (created.id to it) } ?: m }
        aliases.update { it + (uiId to created.id) }
        moveDraft(uiId, created.id)
        applyInitialModel(uiId, created.id)
        applyInitialProject(uiId, created.id)
        chats.update { it - uiId }
        draftSessions.update { it - uiId }
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
        draftSessions.update { it + (placeholder to Session(placeholder, "$title（分叉）", "正在分叉…", clock())) }
        if (fromItemId != null) _errors.tryEmit("Hermes 只能分叉整个会话，已从最新一条开始分叉")
        runScope.launch {
            try {
                val forked = c.forkSession(source) ?: throw HermesError.NotHermes()
                createdAt[forked.id] = clock()
                _sessions.update { list -> listOf(forked.toSession(clock())) + list.filterNot { it.id == forked.id } }
                aliases.update { it + (placeholder to forked.id) }
                draftSessions.update { it - placeholder }
                loadMessages(forked.id)
            } catch (e: HermesError) {
                draftSessions.update { m -> m[placeholder]?.let { m + (placeholder to it.copy(preview = "分叉失败")) } ?: m }
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
            val key = resolve(sessionId).takeUnless { it.startsWith(DraftPrefix) } ?: return@onStart
            // 先显示本机存的记录，同时去服务器拉最新的
            val connection = _activeConnectionId.value
            if (cache != null && connection != null && chats.value[key] == null) {
                val cached = withContext(Dispatchers.IO) { cache.messages(connection, key) }
                if (!cached.isNullOrEmpty()) chats.update { m -> if (m[key] == null) m + (key to ChatState(server = cached)) else m }
            }
            scope.launch { loadMessages(key) }
        }

    override fun runPhase(sessionId: String): Flow<RunPhase> = chatOf(sessionId).map { it?.phase ?: RunPhase.Idle }

    override fun streamingText(sessionId: String): Flow<String?> = chatOf(sessionId).map { st ->
        st?.streaming?.takeIf { it.isNotBlank() && _settings.value.streamReplies }?.let { MediaTags.parse(it).first }
    }

    /** 拉服务器上的聊天记录，和本地还没确认的条目合并。 */
    private suspend fun loadMessages(sessionId: String) {
        val c = client ?: return
        if (capabilities?.sessionMessages == false) return
        try {
            val now = clock()
            val items = c.messages(sessionId).data.mapIndexedNotNull { i, m -> m.toChatItem(i, now, ::cachedImage) }
                .linkQuotes()
                .withSentImages(sessionId)
            val running = runs.containsKey(sessionId)
            updateChat(sessionId) { st -> st.copy(server = items, local = st.local.reconcile(items, running), loaded = true) }
            val connection = scopeId
            if (cache != null && connection != null) withContext(Dispatchers.IO) { cache.saveMessages(connection, sessionId, items) }
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

    // ---- 服务器上的文件 ----

    private val mediaLocks = ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    override suspend fun fetchRemoteMedia(path: String): String? = withContext(Dispatchers.IO) {
        val dir = mediaDir ?: return@withContext null
        val ext = path.substringAfterLast('.', "bin").lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { "bin" }
        val name = sha256Hex("$connectedId:$path").take(40) + "." + ext
        val target = File(File(dir, "remote").apply { mkdirs() }, name)
        if (target.isFile) return@withContext target.path
        val t = tunnel ?: return@withContext null
        mediaLocks.getOrPut(name) { kotlinx.coroutines.sync.Mutex() }.withLock {
            if (target.isFile) return@withLock target.path
            // SFTP 不展开 ~，相对路径就是相对家目录
            val remote = if (path.startsWith("~/")) path.removePrefix("~/") else path
            runCatching { t.download(remote, target) }.map { target.path }.getOrNull()
        }
    }

    override suspend fun fileForOpening(file: FileRef): String? = withContext(Dispatchers.IO) {
        file.localPath?.takeIf { File(it).isFile }?.let { return@withContext it }
        file.inlineText?.let { text ->
            val dir = File(mediaDir ?: return@let null, "text").apply { mkdirs() }
            val ext = file.name.substringAfterLast('.', "txt").filter { it.isLetterOrDigit() }.take(8).ifEmpty { "txt" }
            return@withContext File(dir, sha256Hex(text).take(40) + "." + ext).apply { if (!isFile) writeText(text) }.path
        }
        file.remotePath?.let { fetchRemoteMedia(it) }
    }

    // ---- 发过的图片 ----

    private val sentImagesFile: File? get() = mediaDir?.let { File(it, "sent-images.json") }

    private val sentImages: MutableMap<String, MutableList<SentImages>> by lazy {
        runCatching {
            HermesJson.decodeFromString(SentImagesStore.serializer(), sentImagesFile!!.readText())
                .sessions.mapValues { it.value.toMutableList() }.toMutableMap()
        }.getOrElse { mutableMapOf() }
    }

    private fun rememberSentImages(sessionId: String, out: Outgoing) {
        val paths = out.attachments.displayImages().mapNotNull { it.source }
        if (paths.isEmpty() || mediaDir == null) return
        synchronized(sentImages) {
            val list = sentImages.getOrPut(sessionId) { mutableListOf() }
            list += SentImages(out.text.trim(), paths)
            if (list.size > 300) list.subList(0, list.size - 300).clear()
            runCatching {
                sentImagesFile!!.writeText(HermesJson.encodeToString(SentImagesStore.serializer(), SentImagesStore(sentImages)))
            }
        }
    }

    /**
     * 服务器记录里看不到原图的用户消息，把本机的图换回来：
     * 1. 按文字和顺序对上记住的那条（发送时记下的）；
     * 2. 对不上的（例如记录功能出现之前发的），用缓存目录里时间最接近、发送前不久保存的图片。
     */
    private fun List<ChatItem.Message>.withSentImages(sessionId: String): List<ChatItem.Message> {
        val (remembered, claimed) = synchronized(sentImages) {
            sentImages[sessionId]?.toList().orEmpty() to sentImages.values.flatten().flatMap { it.paths }.toMutableSet()
        }
        val used = BooleanArray(remembered.size)
        fun norm(text: String) = text.replace(Regex("\\s+"), " ").trim()
        // 历史里已经能显示的本机图片也算“用过”，时间匹配不能再分给别人
        forEach { m -> m.images.mapNotNullTo(claimed) { img -> img.source?.takeIf { File(it).isFile } } }
        val orphans by lazy {
            mediaDir?.listFiles { f -> f.isFile && f.extension in ImageExtensions }
                ?.filter { it.path !in claimed }?.sortedBy { it.lastModified() }?.toMutableList() ?: mutableListOf()
        }
        return map { m ->
            if (m.role != Role.User || m.images.isEmpty() && m.text.isBlank()) return@map m
            val needsFix = m.images.isEmpty() || m.images.any { it.source == null || !(File(it.source).isFile || it.source.startsWith("data:")) }
            if (!needsFix) return@map m
            val i = remembered.indices.firstOrNull { !used[it] && norm(remembered[it].text) == norm(m.text) }
            if (i != null) {
                used[i] = true
                val paths = remembered[i].paths.filter { File(it).isFile }
                if (paths.isNotEmpty()) return@map m.withImages(paths)
            }
            // 只有服务器明确记了有图（占位块）时才按时间找，免得给纯文字消息配图
            val missing = m.images.count { it.source == null || !File(it.source).isFile }
            if (missing == 0 || m.images.any { it.source?.startsWith(RemoteMediaPrefix) == true }) return@map m
            val picked = orphans.filter { it.lastModified() in (m.time - 30 * 60_000)..(m.time + 60_000) }.takeLast(missing)
            if (picked.size != missing) return@map m
            orphans.removeAll(picked)
            m.withImages(picked.map { it.path })
        }
    }

    private fun ChatItem.Message.withImages(paths: List<String>) =
        copy(images = paths.mapIndexed { k, p -> ImageRef("$id-s$k", (k * 53f) % 360f, 1.3f, p) })

    // ---- 草稿 ----

    private val _drafts = MutableStateFlow(state.value.drafts)
    override val drafts: StateFlow<Map<String, String>> = _drafts.asStateFlow()
    private val draftAttachments = ConcurrentHashMap<String, List<Attachment>>()
    private var draftSaveJob: Job? = null

    override fun draft(sessionId: String): Draft {
        val key = resolve(sessionId)
        return Draft(_drafts.value[key].orEmpty(), draftAttachments[key].orEmpty())
    }

    override fun saveDraft(sessionId: String, draft: Draft) {
        val key = resolve(sessionId)
        if (draft.attachments.isEmpty()) draftAttachments.remove(key) else draftAttachments[key] = draft.attachments
        val text = draft.text
        if ((_drafts.value[key] ?: "") == text.takeIf { it.isNotBlank() }.orEmpty()) return
        _drafts.update { if (text.isBlank()) it - key else it + (key to text) }
        // 打字时不要每个字都写文件
        draftSaveJob?.cancel()
        draftSaveJob = scope.launch {
            delay(500)
            persist { st -> st.copy(drafts = _drafts.value.filterKeys { !it.startsWith(DraftPrefix) }) }
        }
    }

    private fun moveDraft(from: String, to: String) {
        _drafts.value[from]?.let { text -> _drafts.update { it - from + (to to text) } }
        draftAttachments.remove(from)?.let { draftAttachments[to] = it }
    }

    override fun canSendAnyFile(): Boolean {
        val profile = _connections.value.firstOrNull { it.id == connectedId } ?: return false
        return tunnel != null || (profile.type == ConnectionType.Local && localFileExporter != null)
    }

    private fun publishRuns() {
        _activeRuns.value = runs.keys.toSet()
        val connection = scopeId ?: return
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
        val running = runs[key]
        // 上一条刚发出、还没拿到这一轮的 id：等它开始，再按插话处理（不要并行开第二轮）
        val starting = running == null && outbox.values.any { resolve(it.uiId) == key }
        updateChat(key) { it.copy(local = it.local + LocalEntry(message, it.messageCount), phase = if (running != null && it.phase == RunPhase.AwaitingInput) it.phase else RunPhase.Working, streaming = running?.let { _ -> it.streaming }) }
        outbox[localId] = Outgoing(sessionId, text.trim(), attachments, quote)
        runScope.launch {
            val run = running ?: if (starting) awaitStartedRun(key, localId) else null
            if (run != null) steerOrQueue(key, run, localId) else submit(localId)
        }
    }

    /** 等同一个对话里先发的那条消息变成正在进行的一轮；它发送失败或已经结束就返回 null。 */
    private suspend fun awaitStartedRun(key: String, localId: String): ActiveRun? = withTimeoutOrNull(60_000) {
        while (true) {
            runs[key]?.let { return@withTimeoutOrNull it }
            if (outbox.none { (id, out) -> id != localId && resolve(out.uiId) == key }) return@withTimeoutOrNull runs[key]
            delay(50)
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    /** 插话发出去、还没被这一轮用上的本地消息：run id → 本地消息 id。 */
    private val steered = ConcurrentHashMap<String, MutableList<String>>()

    /**
     * Hermes 还在回复时又发了一句：纯文字就“插话”（Hermes 在下一步看到，不打断它），
     * 带附件或服务器不支持插话就等这一轮结束再作为新消息发。
     */
    private suspend fun steerOrQueue(key: String, run: ActiveRun, localId: String) {
        val out = outbox[localId] ?: return
        val canSteer = out.attachments.isEmpty() && out.quote == null && capabilities?.endpoints?.contains("run_steer") == true
        if (canSteer) {
            try {
                withRetry { client?.steerRun(run.runId, out.text) ?: throw HermesError.Unreachable("还没连上 Hermes") }
                outbox.remove(localId)
                setLocalStatus(key, localId, DeliveryStatus.Sent)
                steered.getOrPut(run.runId) { java.util.Collections.synchronizedList(mutableListOf()) } += localId
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: HermesError.Conflict) {
                // 这一轮正在收尾，不接受插话了：排到下一轮
            } catch (e: HermesError.NotSupported) {
                // 这一轮已经不在了
            } catch (e: HermesError) {
                setLocalStatus(key, localId, DeliveryStatus.Failed)
                _errors.tryEmit("插话没发出去：${e.message}")
                return
            }
        }
        _activeRuns.first { key !in it }
        submit(localId)
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
            val workspace = _sessionGroups.value.folders[sessionId]
            val instructions = ClientInstructions + skillCommandNote(out.text).orEmpty() + workspace?.let(::workspaceNote).orEmpty()
            val (model, provider) = chosenModel(sessionId)
            val accepted = withRetry {
                c.startRun(sessionId, input, idempotencyKey = localId, instructions = instructions, model = model, provider = provider, workspace = workspace)
            }
            val runId = accepted.run_id ?: throw HermesError.NotHermes()
            outbox.remove(localId)
            setLocalStatus(sessionId, localId, DeliveryStatus.Sent)
            rememberSentImages(sessionId, out)
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
        // 发的图片同时存一份原文件到 Hermes 那台机器上：有的 Hermes 版本只把图片转成文字描述、不落盘，
        // 模型要上传附件、用工具处理图片时得有真文件路径
        val photos = out.attachments.filterIsInstance<Attachment.Image>()
        val copies = photos.mapIndexedNotNull { i, image -> imageCopy(image, i + 1, photos.size)?.let { AttachmentText.imageFile(it, i + 1, photos.size) } }
        val text = (listOf(quotedInput(out.quote, out.text)) + blocks + listOfNotNull(copies.takeIf { it.isNotEmpty() }?.joinToString("\n")))
            .filter { it.isNotBlank() }.joinToString("\n\n")
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

    /** 图片原文件放到 Hermes 那台机器上（SSH 上传，或本机 Termux 存到下载目录）；做不到就返回 null，不影响发送。 */
    private fun imageCopy(image: Attachment.Image, index: Int, total: Int): String? {
        val file = File(image.path)
        if (!file.isFile) return null
        val name = "image" + (if (total > 1) "-$index" else "") + "." + file.extension.ifEmpty { "jpg" }
        tunnel?.let { t ->
            return try {
                t.upload(file.path, name)
            } catch (e: Exception) {
                _errors.tryEmit("图片原文件没能传到服务器（图片照常发送）：${e.message}")
                null
            }
        }
        val profile = _connections.value.firstOrNull { it.id == connectedId }
        if (profile?.type != ConnectionType.Local) return null
        return localFileExporter?.invoke(file.path, name, mimeOf(file.name))
    }

    /** 普通文件交给 Hermes：SSH 上传到服务器，本机 Termux 存到下载目录。返回 Hermes 那边能读到的路径。 */
    private fun deliverFile(file: Attachment.BinaryFile): String {
        val profile = _connections.value.firstOrNull { it.id == connectedId }
        tunnel?.let { t ->
            return try {
                t.upload(file.path, file.name)
            } catch (e: Exception) {
                throw HermesError.Ssh("文件上传失败：${e.message}")
            }
        }
        return when (profile?.type) {
            ConnectionType.Ssh -> throw HermesError.Ssh("SSH 隧道没连上")
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
            "completed" -> complete(run, status.output.orEmpty(), status.pending_steer?.takeIf { it.isNotBlank() })
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
            is RunEvent.Completed -> complete(run, event.output, event.pendingSteer)
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

    private fun complete(run: ActiveRun, output: String, pendingSteer: String? = null) {
        if (runs[run.sessionId] !== run) return
        val raw = output.trim().ifEmpty { chats.value[run.sessionId]?.streaming?.trim().orEmpty() }
        // 回复里的 MEDIA:<路径> 是 Hermes 生成的文件：图片显示出来，其它显示成卡片
        val (text, media) = replyMedia(raw)
        val images = media.images.mapIndexed { i, path -> ImageRef("reply-${run.runId}-$i", 200f + i * 40, 1f, RemoteMediaPrefix + path) }
        val files = media.files
        finish(run)
        // 先把结果显示出来，再去服务器拉正式记录对齐
        updateChat(run.sessionId) { st ->
            val reply = if (text.isEmpty() && images.isEmpty() && files.isEmpty()) null else
                LocalEntry(ChatItem.Message("reply-${run.runId}", clock(), Role.Assistant, text, images = images, files = files), st.messageCount, run.runId)
            st.copy(phase = RunPhase.Idle, streaming = null, local = st.local + listOfNotNull(reply))
        }
        val preview = text.replace('\n', ' ').take(80).ifEmpty { if (images.isNotEmpty()) "[图片]" else if (files.isNotEmpty()) "[文件]" else "" }
        updateSession(run.sessionId) { it.copy(preview = preview, updatedAt = clock()) }
        if (text.isNotEmpty()) notify(run.sessionId, ChatNotice.Kind.Reply, text)
        val steeredIds = steered.remove(run.runId).orEmpty().toSet()
        if (pendingSteer != null) {
            // 插话还没交给模型这一轮就结束了：去掉那几条，作为新消息重新发
            updateChat(run.sessionId) { st -> st.copy(local = st.local.filterNot { it.item.id in steeredIds }) }
            sendMessage(run.sessionId, pendingSteer, emptyList())
        }
        scope.launch { reconcileAfterRun(run.sessionId, text) }
    }

    private fun end(run: ActiveRun, error: String?) {
        if (runs[run.sessionId] !== run) return
        steered.remove(run.runId)
        finish(run)
        updateChat(run.sessionId) { it.copy(phase = RunPhase.Idle, streaming = null) }
        if (error != null) {
            _errors.tryEmit("Hermes 出错了：$error")
            notify(run.sessionId, ChatNotice.Kind.Failed, error)
        }
        scope.launch { reconcileAfterRun(run.sessionId, expectedReply = null) }
    }

    /**
     * Hermes 会自动给对话起名：第一条消息一进来先按内容起个临时标题，
     * 再在后台让小模型想一个更好的（几秒后才写进去）。所以一轮结束后隔几次读一下标题。
     */
    private fun refreshTitleSoon(sessionId: String) {
        if (capabilities?.endpoints?.contains("session") == false) return
        scope.launch {
            var last: String? = null
            for (wait in longArrayOf(0, 3_000, 10_000)) {
                delay(wait)
                val dto = try {
                    client?.session(sessionId)
                } catch (e: HermesError) {
                    null
                } ?: continue
                val title = dto.title?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                if (title != last) updateSession(sessionId) { it.copy(title = title) }
                last = title
            }
        }
    }

    /** 一轮结束后以服务器记录为准。服务器写入记录可能比结束事件晚一点，所以没看到回复就稍等再拉。 */
    private suspend fun reconcileAfterRun(sessionId: String, expectedReply: String?) {
        refreshTitleSoon(sessionId)
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

    // ---- 模型 ----

    private val _models = MutableStateFlow<List<ModelOption>>(emptyList())
    override val models: StateFlow<List<ModelOption>> = _models.asStateFlow()
    private val _defaultModelId = MutableStateFlow("")
    override val defaultModelId: StateFlow<String> = _defaultModelId.asStateFlow()

    /** Hermes 自己的默认模型（ModelOption.id）。 */
    private var serverDefaultModel: String? = null

    /** 草稿会话选了模型：等它在服务器上建好再锁定。 */
    private val pendingModels = ConcurrentHashMap<String, ModelOption>()

    /** 读 Hermes 的模型清单：只列已经配好（有 Key / 已登录）且有模型的 Provider，Hermes 当前用的排最前。 */
    private suspend fun loadModels() {
        val c = client ?: return
        if (capabilities?.endpoints?.contains("model_options") != true) return
        try {
            val dto = c.modelOptions()
            val providers = dto.providers.filter { (it.authenticated == true || it.is_current == true) && it.models.isNotEmpty() }
                .sortedByDescending { it.is_current == true || it.slug == dto.provider }
            val options = providers.flatMap { p ->
                p.models.map { m ->
                    val current = m == dto.model && (p.slug == dto.provider || p.is_current == true)
                    ModelOption(
                        id = "${p.slug}:$m",
                        name = m,
                        provider = p.name ?: p.slug,
                        note = if (current) "Hermes 默认" else null,
                        model = m,
                        providerSlug = p.slug,
                    )
                }
            }
            _models.value = options
            serverDefaultModel = options.firstOrNull { it.note == "Hermes 默认" }?.id
            _defaultModelId.value = appDefaultModel()?.id ?: serverDefaultModel.orEmpty()
            _features.update { it + AppFeature.Models }
        } catch (e: HermesError) {
            // 模型清单拿不到不影响聊天
        }
    }

    private fun appDefaultModel(): ModelOption? {
        val id = state.value.defaultModels[scopeId ?: return null] ?: return null
        return _models.value.firstOrNull { it.id == id }
    }

    override fun setSessionModel(sessionId: String, modelId: String) {
        val option = _models.value.firstOrNull { it.id == modelId } ?: return
        val key = resolve(sessionId)
        if (key.startsWith(DraftPrefix)) {
            pendingModels[key] = option
            draftSessions.update { m -> m[key]?.let { m + (key to it.copy(model = option.model)) } ?: m }
            return
        }
        rememberSessionModel(key, option)
        // 锁定是给桌面版、网页版看的；App 自己每一轮都带上模型（/v1/runs 不读这个锁）
        optimistic(key, { it.copy(model = option.model) }) { lockSessionModel(key, option.model, option.providerSlug) }
    }

    private fun sessionModelKey(sessionId: String) = "${scopeId.orEmpty()}/$sessionId"

    private fun rememberSessionModel(sessionId: String, option: ModelOption) {
        if (connectedId == null) return
        persist { st -> st.copy(sessionModels = st.sessionModels + (sessionModelKey(sessionId) to option.id)) }
    }

    /** 这个对话在手机上选过的模型 → (model, provider)；没选过返回 (null, null)，用 Hermes 的默认。 */
    private fun chosenModel(sessionId: String): Pair<String?, String?> {
        val id = state.value.sessionModels[sessionModelKey(sessionId)] ?: return null to null
        val option = _models.value.firstOrNull { it.id == id }
        if (option != null) return option.model to option.providerSlug
        // 模型清单还没读到：id 就是 "provider:model"（模型名里可能也有冒号，provider 里没有）
        val provider = id.substringBefore(':', "")
        val model = id.substringAfter(':', "")
        return if (provider.isEmpty() || model.isEmpty()) null to null else model to provider
    }

    /** “新对话默认模型”：只影响从手机新建的对话（Hermes 自己的默认在服务器配置里，API 改不了）。 */
    override fun setDefaultModel(modelId: String) {
        val connection = scopeId ?: return
        val toServerDefault = modelId == serverDefaultModel
        persist { st -> st.copy(defaultModels = if (toServerDefault) st.defaultModels - connection else st.defaultModels + (connection to modelId)) }
        _defaultModelId.value = modelId
    }

    /** 新会话刚在服务器上建好：把草稿时选的模型（或手机上设的默认模型）锁上。 */
    private suspend fun applyInitialModel(draftId: String, sessionId: String) {
        val choice = pendingModels.remove(draftId) ?: appDefaultModel()?.takeIf { it.id != serverDefaultModel } ?: return
        rememberSessionModel(sessionId, choice)
        try {
            client?.lockSessionModel(sessionId, choice.model, choice.providerSlug)
            updateSession(sessionId) { it.copy(model = choice.model) }
        } catch (e: HermesError) {
            _errors.tryEmit("没能切换到 ${choice.name}：${e.message}")
        }
    }
    // ---- 技能、工具集 ----

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    override val skills: StateFlow<List<Skill>> = _skills.asStateFlow()
    private val _skillsError = MutableStateFlow<String?>(null)
    override val skillsError: StateFlow<String?> = _skillsError.asStateFlow()
    private val _toolsets = MutableStateFlow<List<Toolset>>(emptyList())
    override val toolsets: StateFlow<List<Toolset>> = _toolsets.asStateFlow()

    private suspend fun loadSkills() {
        val c = client ?: return
        if (capabilities?.endpoints?.contains("skills") != true) return
        _features.update { it + AppFeature.Skills }
        try {
            _skills.value = c.skills().data.map { Skill(it.name, it.description.orEmpty(), it.category.orEmpty()) }
            _skillsError.value = null
        } catch (e: HermesError.Server) {
            // 某些 Hermes 版本这个接口自己会报错（500）。有 SSH 就直接读技能目录
            val fromDisk = skillsOverSsh()
            if (fromDisk != null) {
                _skills.value = fromDisk
                _skillsError.value = null
                return
            }
            _skillsError.value = "Hermes 读取技能列表时出错（${e.message}）。这是 Hermes 那边的问题，聊天时技能照常可用；升级 Hermes 后再试。"
        } catch (e: HermesError) {
            if (_skills.value.isEmpty()) _skillsError.value = "读不到技能列表：${e.message}"
        }
    }

    /**
     * 经 SSH 读 `~/.hermes/skills/<分类>/<名字>/SKILL.md` 开头的 name / description。
     * 读不了（没有 SSH、目录不在）返回 null。
     */
    private suspend fun skillsOverSsh(): List<Skill>? {
        val t = tunnel ?: return null
        val profile = profileFlag().removePrefix(" -p ")
        val home = if (profile.isEmpty()) "\${HERMES_HOME:-\$HOME/.hermes}" else "\$HOME/.hermes/profiles/$profile"
        val script = "cd \"$home/skills\" 2>/dev/null || exit 3; find . -name SKILL.md -not -path '*/.*/*' | sort | while read -r f; do " +
            "printf '%s\\t' \"\$f\"; awk '/^---/{n++; next} n==1 && /^(name|description):/{k=\$1; sub(/^[a-z]+:[ ]*/, \"\"); printf \"%s%s\\t\", k, \$0} n>=2{exit}' \"\$f\"; echo; done"
        val (code, output) = try {
            withContext(Dispatchers.IO) { t.exec(script) }
        } catch (e: IOException) {
            return null
        }
        if (code != 0) return null
        return parseSkillListing(output).takeIf { it.isNotEmpty() }
    }

    private suspend fun loadToolsets() {
        val c = client ?: return
        if (capabilities?.endpoints?.contains("toolsets") != true) return
        try {
            _toolsets.value = c.toolsets().data.map {
                Toolset(it.name, it.label?.takeIf { l -> l.isNotBlank() } ?: it.name, it.description.orEmpty(), it.tools, it.enabled == true, it.configured != false)
            }
            _features.update { it + AppFeature.Toolsets }
        } catch (e: HermesError) {
            // 读不到就不显示
        }
    }

    override suspend fun refreshCapabilities() {
        coroutineScope {
            launch { loadSkills() }
            launch { loadToolsets() }
        }
    }

    private val _toolsetsBusy = MutableStateFlow<Set<String>>(emptySet())
    override val toolsetsBusy: StateFlow<Set<String>> = _toolsetsBusy.asStateFlow()

    override fun canToggleToolsets(): Boolean = tunnel != null

    /** 当前连接的 Profile（hermes 命令要用 -p 指定）。 */
    private fun profileFlag(): String =
        connections.value.firstOrNull { it.id == connectedId }?.let(::effectiveProfile)?.profile?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { " -p " + it.replace(Regex("[^A-Za-z0-9_.-]"), "") }.orEmpty()

    /** API 不能改工具集，经 SSH 运行 `hermes tools enable|disable … --platform api_server`（改的是 config.yaml，下一条消息起生效）。 */
    override fun toggleToolset(name: String) {
        val t = tunnel ?: return notYet("不经 SSH 开关工具集")
        val toolset = _toolsets.value.firstOrNull { it.name == name } ?: return
        if (name in _toolsetsBusy.value) return
        val enable = !toolset.enabled
        _toolsetsBusy.update { it + name }
        scope.launch {
            try {
                val safe = name.replace(Regex("[^A-Za-z0-9_.:-]"), "")
                val (code, output) = withContext(Dispatchers.IO) {
                    t.exec("hermes${profileFlag()} tools ${if (enable) "enable" else "disable"} $safe --platform api_server")
                }
                if (code != 0) {
                    val hint = if (code == 127 || "command not found" in output) "服务器上找不到 hermes 命令" else output.lines().lastOrNull { it.isNotBlank() }?.take(200) ?: "退出码 $code"
                    _errors.tryEmit("没能${if (enable) "开启" else "关闭"} ${toolset.label}：$hint")
                }
                loadToolsets()
            } catch (e: IOException) {
                _errors.tryEmit("SSH 执行失败：${e.message}")
            } finally {
                _toolsetsBusy.update { it - name }
            }
        }
    }
    // ---- 定时任务 ----

    private val _jobs = MutableStateFlow<List<CronJob>>(emptyList())
    override val jobs: StateFlow<List<CronJob>> = _jobs.asStateFlow()

    /** 读任务列表；能读到就说明这台 Hermes 支持定时任务接口（它不在能力清单里，只能试）。 */
    private suspend fun loadJobs() {
        val c = client ?: return
        try {
            val running = _jobs.value.filter { it.runningNow }.map { it.id }.toSet()
            _jobs.value = c.jobs().jobs.map { it.toCronJob(clock()).copy(runningNow = it.id in running) }
            _features.update { it + AppFeature.Jobs }
        } catch (e: HermesError.NotSupported) {
            _features.update { it - AppFeature.Jobs }
        } catch (e: HermesError.Server) {
            if (e.status == 501) _features.update { it - AppFeature.Jobs }
        } catch (e: HermesError) {
            // 网络问题：保留旧列表
        }
    }

    override suspend fun refreshJobs() = loadJobs()

    private fun replaceJob(dto: JobDto?) {
        dto ?: return
        val job = dto.toCronJob(clock())
        _jobs.update { list -> if (list.any { it.id == job.id }) list.map { if (it.id == job.id) job.copy(runningNow = it.runningNow) else it } else list + job }
    }

    private fun jobCall(what: String, rollback: List<CronJob>? = null, call: suspend HermesClient.() -> Unit) {
        scope.launch {
            try {
                client?.call() ?: throw HermesError.Unreachable("未连接")
            } catch (e: HermesError) {
                rollback?.let { _jobs.value = it }
                _errors.tryEmit("$what：${e.message}")
            }
        }
    }

    override fun setJobEnabled(id: String, enabled: Boolean) {
        val before = _jobs.value
        _jobs.update { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
        jobCall(if (enabled) "恢复任务失败" else "暂停任务失败", before) { replaceJob(jobAction(id, if (enabled) "resume" else "pause")) }
    }

    /**
     * 立即运行。Hermes 只是把下次运行时间改成“现在”，调度器下一轮（一分钟内）才真正执行，
     * 所以这里等它的“上次运行时间”变了，再刷新会话列表、发通知。
     */
    override fun runJobNow(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        _jobs.update { list -> list.map { if (it.id == id) it.copy(runningNow = true) else it } }
        scope.launch {
            val c = client ?: return@launch
            try {
                val before = c.job(id)?.last_run_at
                c.jobAction(id, "run")
                val deadline = clock() + 15 * 60_000
                var done: JobDto? = null
                while (clock() < deadline) {
                    delay(jobPollMillis)
                    val now = runCatching { c.job(id) }.getOrNull() ?: continue
                    if (now.last_run_at != null && now.last_run_at != before) {
                        done = now
                        break
                    }
                }
                _jobs.update { list -> list.map { if (it.id == id) it.copy(runningNow = false) else it } }
                replaceJob(done)
                loadSessions()
                if (done != null) {
                    val run = _sessions.value.filter { it.source.id == "cron" && it.title.startsWith(job.name + " · ") }.maxByOrNull { it.updatedAt }
                    val failed = done.last_status != null && done.last_status != "ok"
                    // 列表预览是 Hermes 加的系统说明，通知里换成它真正的回复
                    val reply = run?.let { r ->
                        runCatching { c.messages(r.id).data.lastOrNull { it.role == "assistant" }?.toChatItem(0, clock())?.text }.getOrNull()
                    }
                    _notices.tryEmit(
                        ChatNotice(
                            run?.id ?: "",
                            job.name,
                            if (failed) "运行失败：${done.last_error ?: done.last_status}" else (reply?.let { if (it.trim() == "[SILENT]") "没有新内容" else it }?.takeIf { it.isNotBlank() } ?: run?.preview?.takeIf { it.isNotBlank() } ?: "运行完成"),
                            if (failed) ChatNotice.Kind.Failed else ChatNotice.Kind.Reply,
                        ),
                    )
                }
            } catch (e: HermesError) {
                _jobs.update { list -> list.map { if (it.id == id) it.copy(runningNow = false) else it } }
                _errors.tryEmit("运行「${job.name}」失败：${e.message}")
            }
        }
    }

    override fun saveJob(job: CronJob) {
        val fields = HermesClient.patch {
            put("name", job.name)
            put("prompt", job.prompt)
            put("schedule", job.cron)
            put("deliver", job.deliver)
        }
        val exists = _jobs.value.any { it.id == job.id }
        jobCall(if (exists) "保存任务失败" else "新建任务失败") {
            replaceJob(if (exists) updateJob(job.id, fields) else createJob(fields))
        }
    }

    override fun deleteJob(id: String) {
        val before = _jobs.value
        _jobs.update { list -> list.filterNot { it.id == id } }
        jobCall("删除任务失败", before) { deleteJob(id) }
    }

    // ---- 分组 ----

    private val _sessionGroups = MutableStateFlow(SessionGrouping())
    override val sessionGroups: StateFlow<SessionGrouping> = _sessionGroups.asStateFlow()

    /** 经 SSH 读 Hermes 的项目和每个会话的工作目录；没有 SSH 或读不了就保持原样（不打扰）。 */
    private suspend fun loadGroups() {
        val admin = admin() ?: return
        val connection = scopeId ?: return
        try {
            val grouping = admin.sessionGroups()
            _sessionGroups.value = grouping
            cache?.let { c -> withContext(Dispatchers.IO) { c.saveGroups(connection, grouping) } }
        } catch (e: AdminException) {
            // 分组只是锦上添花，失败不弹提示；诊断信息里能看到
            synchronized(recentErrors) { recentErrors.addLast("${java.time.Instant.ofEpochMilli(clock())} 读分组失败：${e.message}") }
        }
    }

    // ---- 项目 ----

    /** 草稿 id → 选好的项目。 */
    private val pendingProjects = ConcurrentHashMap<String, ProjectInfo>()

    override fun setDraftProject(draftId: String, project: ProjectInfo?) {
        if (project == null) pendingProjects.remove(draftId) else pendingProjects[draftId] = project
    }

    /** 新会话建好了：先在本地把它记到项目里（第一条消息就带上工作目录），再经 SSH 真正移过去。 */
    private fun applyInitialProject(draftId: String, sessionId: String) {
        val project = pendingProjects.remove(draftId) ?: return
        val path = project.path ?: return
        markMoved(sessionId, project.name, path)
        scope.launch { moveOnServer(sessionId, path) }
    }

    override fun moveToProject(sessionId: String, project: ProjectInfo?) {
        val id = resolve(sessionId)
        if (runs.containsKey(id)) {
            _errors.tryEmit("Hermes 正在回复这个对话，等它说完再移动")
            return
        }
        val before = _sessionGroups.value
        if (project?.path != null) markMoved(id, project.name, project.path) else {
            _sessionGroups.update { g -> g.copy(of = g.of - id, folders = g.folders - id) }
        }
        scope.launch {
            if (!moveOnServer(id, project?.path)) _sessionGroups.value = before
        }
    }

    private fun markMoved(sessionId: String, group: String, path: String) {
        _sessionGroups.update { g ->
            g.copy(
                groups = if (group in g.groups) g.groups else listOf(group) + g.groups,
                of = g.of + (sessionId to group),
                folders = g.folders + (sessionId to path),
            )
        }
    }

    private suspend fun moveOnServer(sessionId: String, path: String?): Boolean {
        val admin = admin() ?: run {
            _errors.tryEmit("移动对话要经 SSH 改 Hermes 的记录，给这个连接配上 SSH 后再试")
            return false
        }
        return try {
            admin.moveSession(sessionId, path)
            loadGroups()
            true
        } catch (e: AdminException) {
            _errors.tryEmit("没能移动对话：${e.message}")
            false
        }
    }

    override suspend fun refreshGroups() = loadGroups()

    // ---- 归档 ----

    /** App 里归档的会话（存在本机缓存，Hermes 的会话列表不返回归档的）。 */
    private val _archivedLocal = MutableStateFlow<List<Session>>(emptyList())

    /** 归档请求还没回来的会话：这期间刷新列表还会看到它，不能当成“在别处取消了归档”。 */
    private val archivingNow: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override val archivedSessions: StateFlow<List<Session>> = combine(_archivedLocal, _sessionGroups) { local, grouping ->
        val known = local.map { it.id }.toSet()
        (local + grouping.archived.filter { it.id !in known }.map { Session(it.id, it.title, "", it.updatedAt) })
            .sortedByDescending { it.updatedAt }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun saveArchived() {
        val connection = scopeId ?: _activeConnectionId.value?.let(::scopeOf) ?: return
        val snapshot = _archivedLocal.value
        scope.launch(Dispatchers.IO) { cache?.saveArchived(connection, snapshot) }
    }

    override fun setArchived(id: String, archived: Boolean) {
        val real = resolve(id)
        val c = client ?: return run { _errors.tryEmit("还没连上 Hermes") }
        if (archived) {
            val session = _sessions.value.firstOrNull { it.id == real } ?: return
            _sessions.update { list -> list.filterNot { it.id == real } }
            _archivedLocal.update { list -> listOf(session.copy(running = false, unread = 0)) + list.filterNot { it.id == real } }
            saveArchived()
            archivingNow += real
            scope.launch {
                try {
                    c.patchSession(real, HermesClient.patch { put("archived", true) })
                    archivingNow -= real
                } catch (e: HermesError) {
                    archivingNow -= real
                    _archivedLocal.update { list -> list.filterNot { it.id == real } }
                    _sessions.update { list -> if (list.any { it.id == real }) list else listOf(session) + list }
                    saveArchived()
                    _errors.tryEmit("归档失败：${e.message}")
                }
            }
        } else {
            val local = _archivedLocal.value.firstOrNull { it.id == real }
            _archivedLocal.update { list -> list.filterNot { it.id == real } }
            _sessionGroups.update { g -> g.copy(archived = g.archived.filterNot { it.id == real }) }
            saveArchived()
            scope.launch {
                try {
                    c.patchSession(real, HermesClient.patch { put("archived", false) })
                    loadSessions()
                } catch (e: HermesError) {
                    if (local != null) _archivedLocal.update { list -> listOf(local) + list }
                    saveArchived()
                    _errors.tryEmit("取消归档失败：${e.message}")
                }
            }
        }
    }

    // ---- 诊断 ----

    /** 最近的错误提示（带时间），导出诊断信息时附上。 */
    private val recentErrors = java.util.ArrayDeque<String>()

    private fun watchErrorsForDiagnostics() {
        scope.launch {
            _errors.collect { message ->
                synchronized(recentErrors) {
                    recentErrors.addLast("${java.time.Instant.ofEpochMilli(clock())} $message")
                    while (recentErrors.size > 40) recentErrors.removeFirst()
                }
            }
        }
    }

    /** 后台里没接住的异常（App 不退出）：提示一句，记进诊断信息。 */
    fun reportUnexpected(error: Throwable) {
        val text = "${error.javaClass.simpleName}: ${error.message ?: ""}".take(300)
        synchronized(recentErrors) { recentErrors.addLast("${java.time.Instant.ofEpochMilli(clock())} 未处理的异常 $text") }
        _errors.tryEmit("出了点问题（App 没有退出）：$text")
    }

    override fun diagnosticsReport(): String = buildString {
        val profile = _connections.value.firstOrNull { it.id == _activeConnectionId.value }
        val caps = capabilities
        appendLine("连接：${profile?.type ?: "无"}" + (profile?.let { " · 端口 ${it.port}" + (if (!it.sshUser.isNullOrBlank()) " · SSH 端口 ${it.sshPort}" else "") + (it.profile?.let { p -> " · Profile $p" } ?: "") } ?: ""))
        appendLine("状态：${_connectionState.value}")
        appendLine("SSH：${if (tunnel != null) "已连上" else "未使用"}")
        appendLine("服务器版本：${caps?.version ?: "未知"}${if (caps?.probed == true) "（靠探测）" else ""}")
        appendLine("服务器接口：${caps?.endpoints?.sorted()?.joinToString(", ") ?: "未知"}")
        appendLine("App 功能：${_features.value.joinToString(", ")}")
        appendLine("会话数：${_sessions.value.size}；进行中：${runs.size}")
        appendLine("模型：${_models.value.size} 个；技能：${_skills.value.size} 个${_skillsError.value?.let { "（$it）" } ?: ""}；工具集：${_toolsets.value.size} 个")
        appendLine()
        appendLine("最近的错误：")
        val errors = synchronized(recentErrors) { recentErrors.toList() }
        if (errors.isEmpty()) appendLine("（没有）") else errors.forEach { appendLine(it) }
        com.hermes.mobile.data.store.CrashLog.recent()?.let {
            appendLine()
            appendLine("闪退记录：")
            appendLine(it)
        }
    }

    // ---- 管理 ----

    private var adminFor: Pair<SshTunnel, HermesAdmin>? = null

    override fun admin(): AdminApi? {
        val t = tunnel ?: return null
        val profile = _connections.value.firstOrNull { it.id == connectedId }?.let(::effectiveProfile)?.profile
        adminFor?.let { (tt, a) -> if (tt === t && a.profileName == profile) return a }
        return HermesAdmin(t, profile).also { adminFor = t to it }
    }

    // ---- Bot ----

    private val _bots = MutableStateFlow<com.hermes.mobile.data.model.BotRoster?>(null)
    override val bots: StateFlow<com.hermes.mobile.data.model.BotRoster?> = _bots.asStateFlow()
    private val _activeBot = MutableStateFlow<String?>(null)
    override val activeBot: StateFlow<String?> = _activeBot.asStateFlow()
    private val _botsError = MutableStateFlow<String?>(null)
    override val botsError: StateFlow<String?> = _botsError.asStateFlow()

    override suspend fun refreshBots() {
        val connection = connectedId ?: return
        val admin = admin() ?: run {
            _botsError.value = "看 bot 要经 SSH 读 Hermes 的 Profile，给这个连接配上 SSH 后再试"
            return
        }
        try {
            val roster = admin.bots()
            _bots.value = roster
            _botsError.value = null
            cache?.let { c -> withContext(Dispatchers.IO) { c.saveBots(connection, roster) } }
        } catch (e: AdminException) {
            _botsError.value = e.message
        }
    }

    override suspend fun switchBot(name: String): com.hermes.mobile.data.model.BotSwitch {
        val connection = connectedId ?: _activeConnectionId.value ?: return com.hermes.mobile.data.model.BotSwitch.Failed("还没连上 Hermes")
        val profile = _connections.value.firstOrNull { it.id == connection } ?: return com.hermes.mobile.data.model.BotSwitch.Failed("找不到这个连接")
        if (name == (_activeBot.value ?: baseBot(profile)) && _connectionState.value is ConnectionState.Connected) return com.hermes.mobile.data.model.BotSwitch.Done
        if (name != baseBot(profile)) {
            // 用 bot 自己的 key：手机上没有就去服务器上读（可能电脑版已经配过）
            if (botKeyOf(connection, name) == null) {
                val key = try {
                    admin()?.botKey(name)
                } catch (e: AdminException) {
                    return com.hermes.mobile.data.model.BotSwitch.Failed(e.message ?: "读不到 bot 的 key")
                } ?: return com.hermes.mobile.data.model.BotSwitch.NeedsAccess
                persist { st -> st.copy(keys = st.keys + (botKeyName(connection, name) to secrets.encrypt(key))) }
            }
            if (_bots.value?.multiplex != true) return com.hermes.mobile.data.model.BotSwitch.NeedsAccess
        }
        persist { st -> st.copy(activeBots = if (name == baseBot(profile)) st.activeBots - connection else st.activeBots + (connection to name)) }
        connect(connection)
        // 等连上；这个 bot 在 gateway 上没开（multiplex 没生效）会连不上
        val result = withTimeoutOrNull(30_000) { _connectionState.first { it is ConnectionState.Connected || it is ConnectionState.Failed } }
        return when (result) {
            is ConnectionState.Connected -> com.hermes.mobile.data.model.BotSwitch.Done
            is ConnectionState.Failed -> com.hermes.mobile.data.model.BotSwitch.Failed(result.reason)
            else -> com.hermes.mobile.data.model.BotSwitch.Failed("连接超时")
        }
    }

    override suspend fun enableBotAccess(name: String, progress: (String) -> Unit): String? {
        val connection = connectedId ?: return "还没连上 Hermes"
        val admin = admin() ?: return "开通要经 SSH 改 Hermes 的配置，给这个连接配上 SSH 后再试"
        return try {
            progress("正在给 bot 生成自己的 API Key…")
            val access = admin.enableBotAccess(name)
            persist { st -> st.copy(keys = st.keys + (botKeyName(connection, name) to secrets.encrypt(access.key))) }
            if (access.restartNeeded) {
                progress("已打开 multiplex_profiles，正在重启 gateway（正在进行的回复会中断）…")
                admin.restartGateway()
                // 先等它停下，再等它回来（最多一分半）
                progress("等 gateway 重新启动…")
                delay(5000)
                val back = withTimeoutOrNull(90_000) {
                    while (true) {
                        if (runCatching { client?.health() }.getOrNull() != null) break
                        delay(2000)
                    }
                    true
                }
                if (back == null) return "gateway 没有自己重新启动起来。key 已经写好，请在电脑上启动 Hermes gateway 后再切换到这个 bot"
            }
            refreshBots()
            progress("正在切换到这个 bot…")
            when (val r = switchBot(name)) {
                com.hermes.mobile.data.model.BotSwitch.Done -> null
                com.hermes.mobile.data.model.BotSwitch.NeedsAccess -> "已经写好了 key，但 gateway 还没生效：请在电脑上重启 Hermes gateway 后再试"
                is com.hermes.mobile.data.model.BotSwitch.Failed -> "已经写好了 key，但还连不上这个 bot（${r.reason}）。如果 gateway 没有自动重启，请在电脑上重启 Hermes gateway 后再试"
            }
        } catch (e: AdminException) {
            e.message ?: "开通失败"
        }
    }

    override fun openBotChat(): String? {
        val bot = _bots.value?.bots?.firstOrNull { it.name == _activeBot.value }
        // 固定对话按标题找（和桌面版一样）：先看会话列表，再看 bot 列表里记的
        _sessions.value.firstOrNull { it.title == BotChatTitle }?.let { return it.id }
        bot?.canonical?.id?.let { return it }
        val draft = createSession() ?: return null
        renameSession(draft, BotChatTitle)
        return draft
    }

    override suspend fun botAvatar(bot: com.hermes.mobile.data.model.Bot): ByteArray? {
        val path = bot.avatarPath ?: return null
        val connection = connectedId ?: return null
        // 换头像后文件名不变，按修改时间区分
        val key = "$path@${bot.avatarVersion}"
        cache?.avatar(connection, key)?.let { return it }
        val bytes = runCatching { admin()?.readRemoteFile(path) }.getOrNull() ?: return null
        cache?.saveAvatar(connection, key, bytes)
        return bytes
    }

    override suspend fun botDetails(name: String): Result<com.hermes.mobile.data.model.BotDetails> {
        val admin = admin() ?: return Result.failure(AdminException("改 bot 要经 SSH，给这个连接配上 SSH 后再试"))
        return try {
            Result.success(admin.describeBot(name))
        } catch (e: AdminException) {
            Result.failure(e)
        }
    }

    override suspend fun saveBot(edit: com.hermes.mobile.data.model.BotEdit, create: Boolean): Result<com.hermes.mobile.data.model.BotSaved> {
        val admin = admin() ?: return Result.failure(AdminException("改 bot 要经 SSH，给这个连接配上 SSH 后再试"))
        return try {
            val saved = if (create) admin.createBot(edit) else admin.configureBot(edit)
            refreshBots()
            Result.success(saved)
        } catch (e: AdminException) {
            Result.failure(e)
        }
    }

    override suspend fun deleteBot(name: String): String? {
        val connection = connectedId ?: return "还没连上 Hermes"
        val profile = _connections.value.firstOrNull { it.id == connection } ?: return "找不到这个连接"
        if (name == baseBot(profile)) return "这个连接本身用的就是它，不能在这里删"
        // 正在用它：先换回默认 bot，删完再连会连不上
        if (_activeBot.value == name) {
            when (val r = switchBot(baseBot(profile))) {
                is com.hermes.mobile.data.model.BotSwitch.Failed -> return "没能先换回默认 bot：${r.reason}"
                else -> Unit
            }
        }
        val admin = admin() ?: return "删 bot 要经 SSH，给这个连接配上 SSH 后再试"
        return try {
            admin.deleteBot(name)
            persist { st -> st.copy(keys = st.keys - botKeyName(connection, name), activeBots = if (st.activeBots[connection] == name) st.activeBots - connection else st.activeBots) }
            refreshBots()
            null
        } catch (e: AdminException) {
            e.message ?: "删除失败"
        }
    }

    override suspend fun gatewayStatus(): GatewayStatus? {
        val c = client ?: return null
        return try {
            c.healthDetailed().toGatewayStatus()
        } catch (e: HermesError.NotSupported) {
            null
        } catch (e: HermesError) {
            _errors.tryEmit("读不到 gateway 状态：${e.message}")
            null
        }
    }

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

    /** 项目里的对话：告诉模型这一轮在项目文件夹里干活（API 本身还不支持按对话指定工作目录）。 */
    private fun workspaceNote(folder: String): String =
        "\n\nThis conversation belongs to the project folder $folder on the Hermes machine. Treat it as the working " +
            "directory: run shell commands from there (cd into it first) and resolve relative file paths against it."

    /**
     * `/技能名 …` 开头的消息：Hermes 的 API 不像聊天软件那样展开技能命令，
     * 所以在这一轮的补充说明里告诉模型去加载这个技能（不进聊天记录）。
     */
    private fun skillCommandNote(text: String): String? {
        val name = SkillCommand.find(text)?.groupValues?.get(1) ?: return null
        val known = _skills.value.map { it.name }
        if (known.isNotEmpty() && name !in known) return null
        return "\n\nThe user's message starts with /$name, a Hermes skill command. Load the \"$name\" skill with skill_view " +
            "and follow it for this request (if no such skill exists, treat the message as plain text)."
    }

    private fun sha256Hex(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        /**
         * 每轮对话附带给模型的客户端说明（Hermes 追加在系统提示后面，不存进聊天记录）。
         * Hermes 对 API 客户端的默认说法是“不支持 Markdown、runs 接口发不了文件、只写纯文本路径”，
         * 这个 App 两样都支持，告诉模型按 App 的方式来。
         */
        val SkillCommand = Regex("""^/([A-Za-z0-9][\w.:-]*)(\s|$)""")

        const val ClientInstructions =
            "Client note: the user is chatting through the Hermes Mobile Android app, which renders Markdown " +
                "(headings, lists, code blocks, tables) and supports file delivery on this endpoint. To send the user " +
                "ANY file you created or want to share (images, documents, archives, audio...), put MEDIA:/absolute/path " +
                "on its own line; the app shows images inline and turns other files into cards the user can open or save. " +
                "Prefer that over writing the bare path. This overrides the generic API-server delivery advice."

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
        /** bot 的固定对话的标题（Hermes 桌面版按这个标题认）。 */
        const val BotChatTitle = "Bot Chat"
        val ApprovalOrder = listOf("once", "session", "always", "deny")
    }

    // 放在最后：自动连接会用到上面所有状态，必须等它们都初始化完（否则重启 App 时会崩溃）
    /** 会话列表变了（改名、新消息预览……）就写回缓存，攒一会儿再写。 */
    private fun saveSessionsToCache() {
        val connection = scopeId ?: return
        if (cache == null || !sessionsFresh) return
        val snapshot = _sessions.value
        scope.launch(Dispatchers.IO) { cache.saveSessions(connection, snapshot) }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun watchSessionsForCache() {
        if (cache == null) return
        scope.launch { _sessions.debounce(1_000).collect { saveSessionsToCache() } }
    }

    init {
        watchErrorsForDiagnostics()
        watchSessionsForCache()
        _activeConnectionId.value?.let { connect(it) }
    }
}

/** 一条带图消息：文字和本机缓存的图片路径。服务器有的模式下只记 [screenshot]，靠它把图找回来。 */
@kotlinx.serialization.Serializable
internal data class SentImages(val text: String, val paths: List<String>)

@kotlinx.serialization.Serializable
internal data class SentImagesStore(val sessions: Map<String, List<SentImages>> = emptyMap())
