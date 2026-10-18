package com.hermes.mobile.data.fake

import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.model.AppFeature
import com.hermes.mobile.data.model.AppSettings
import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionState
import com.hermes.mobile.data.model.ConnectionTestStep
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.CronJob
import com.hermes.mobile.data.model.DeliveryStatus
import com.hermes.mobile.data.model.ImageRef
import com.hermes.mobile.data.model.JobRunResult
import com.hermes.mobile.data.model.ModelOption
import com.hermes.mobile.data.model.Quote
import com.hermes.mobile.data.model.Role
import com.hermes.mobile.data.model.RunPhase
import com.hermes.mobile.data.model.Session
import com.hermes.mobile.data.model.Skill
import com.hermes.mobile.data.model.TestStepStatus
import com.hermes.mobile.data.model.Toolset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 模拟 Hermes 的行为，让每个界面都能真实地点一点、看一看：
 * 发消息后先“正在输入”，再整条出现回复；消息里带“删除/重启/清理”会弹出审批卡片。
 */
class FakeHermesRepository(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 模拟 Hermes 思考多久才回复。 */
    private val replyDelayMs: Long = 1200,
) : HermesRepository {

    private var idSeq = 0
    private fun newId(prefix: String) = "$prefix-${clock()}-${idSeq++}"

    // ---- 连接 ----

    private val _connections = MutableStateFlow(SampleData.connections)
    override val connections: StateFlow<List<ConnectionProfile>> = _connections.asStateFlow()

    private val _activeConnectionId = MutableStateFlow<String?>(SampleData.connections.first().id)
    override val activeConnectionId: StateFlow<String?> = _activeConnectionId.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connected(23, "v0.9.2"))
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    override fun switchConnection(id: String) {
        _activeConnectionId.value = id
        _connectionState.value = ConnectionState.Connecting
        scope.launch {
            delay(900)
            _connectionState.value = ConnectionState.Connected(latencyMs = (8..60).random(), serverVersion = "v0.9.2")
        }
    }

    override val features: StateFlow<Set<AppFeature>> = MutableStateFlow(AppFeature.entries.toSet())

    override fun saveConnection(profile: ConnectionProfile, apiKey: String?) {
        _connections.update { list ->
            if (list.any { it.id == profile.id }) list.map { if (it.id == profile.id) profile else it } else list + profile
        }
    }

    override fun deleteConnection(id: String) {
        _connections.update { list -> list.filterNot { it.id == id } }
        if (_activeConnectionId.value == id) _activeConnectionId.value = _connections.value.firstOrNull()?.id
    }

    override fun testConnection(profile: ConnectionProfile, apiKey: String?): Flow<List<ConnectionTestStep>> = flow {
        val titles = buildList {
            add("网络可达")
            if (profile.type == ConnectionType.Ssh) add("建立 SSH 隧道")
            if (profile.type == ConnectionType.Https) add("TLS 证书")
            add("健康检查 /health")
            add("API Key 鉴权")
            add("能力探测 /v1/capabilities")
        }
        var steps = titles.map { ConnectionTestStep(it, TestStepStatus.Pending) }
        emit(steps)
        for (i in titles.indices) {
            steps = steps.mapIndexed { index, step -> if (index == i) step.copy(status = TestStepStatus.Running) else step }
            emit(steps)
            delay(450)
            val detail = when {
                titles[i].startsWith("网络") -> "${(8..40).random()} ms"
                titles[i].startsWith("能力") -> "会话、任务、审批均可用"
                else -> null
            }
            steps = steps.mapIndexed { index, step -> if (index == i) step.copy(status = TestStepStatus.Passed, detail = detail) else step }
            emit(steps)
        }
    }

    // ---- 会话 ----

    private val _sessions = MutableStateFlow(SampleData.sessions(clock()))
    override val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    override fun session(id: String): Flow<Session?> = _sessions.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun refreshSessions() {
        delay(800)
    }

    override fun createSession(): String {
        val id = newId("s")
        _sessions.update { listOf(Session(id, "新对话", "", clock(), model = currentModelName())) + it }
        items.update { it + (id to emptyList()) }
        return id
    }

    override fun renameSession(id: String, title: String) = updateSession(id) { it.copy(title = title) }

    override fun deleteSession(id: String) = _sessions.update { list -> list.filterNot { it.id == id } }

    override fun togglePin(id: String) = updateSession(id) { it.copy(pinned = !it.pinned) }

    override fun forkSession(id: String, fromItemId: String?): String {
        val source = _sessions.value.firstOrNull { it.id == id }
        val forkId = newId("s")
        val copied = itemsOf(id).let { list ->
            val cut = fromItemId?.let { target -> list.indexOfFirst { it.id == target } } ?: -1
            if (cut >= 0) list.take(cut + 1) else list
        }
        items.update { it + (forkId to copied) }
        _sessions.update { listOf(Session(forkId, "${source?.title ?: "对话"}（分支）", source?.preview ?: "", clock(), model = source?.model)) + it }
        return forkId
    }

    private fun updateSession(id: String, transform: (Session) -> Session) =
        _sessions.update { list -> list.map { if (it.id == id) transform(it) else it } }

    // ---- 聊天 ----

    private val items = MutableStateFlow<Map<String, List<ChatItem>>>(emptyMap())
    private val phases = MutableStateFlow<Map<String, RunPhase>>(emptyMap())
    private val streaming = MutableStateFlow<Map<String, String?>>(emptyMap())
    private val runningJobs = mutableMapOf<String, Job>()

    private fun itemsOf(sessionId: String): List<ChatItem> =
        items.value[sessionId] ?: SampleData.chat(sessionId, clock()).also { initial ->
            items.update { it + (sessionId to initial) }
        }

    private fun editItems(sessionId: String, transform: (List<ChatItem>) -> List<ChatItem>) {
        val current = itemsOf(sessionId)
        items.update { it + (sessionId to transform(current)) }
    }

    override fun chatItems(sessionId: String): Flow<List<ChatItem>> {
        itemsOf(sessionId)
        return items.map { it[sessionId].orEmpty() }
    }

    override fun runPhase(sessionId: String): Flow<RunPhase> = phases.map { it[sessionId] ?: initialPhase(sessionId) }

    private fun initialPhase(sessionId: String): RunPhase =
        if (itemsOf(sessionId).any { it is ChatItem.Clarify && it.answer == null }) RunPhase.AwaitingInput else RunPhase.Idle

    override fun streamingText(sessionId: String): Flow<String?> = streaming.map { it[sessionId] }

    private fun setPhase(sessionId: String, phase: RunPhase) {
        phases.update { it + (sessionId to phase) }
        updateSession(sessionId) { it.copy(running = phase == RunPhase.Working) }
    }

    override fun sendMessage(sessionId: String, text: String, images: List<ImageRef>, quote: Quote?) {
        val message = ChatItem.Message(newId("m"), clock(), Role.User, text, images, DeliveryStatus.Sending, quote)
        editItems(sessionId) { it + message }
        updateSession(sessionId) { session ->
            session.copy(
                preview = text.ifBlank { "[图片]" },
                updatedAt = clock(),
                title = if (session.title == "新对话" && text.isNotBlank()) text.take(16) else session.title,
            )
        }
        startRun(sessionId) {
            delay(350)
            editItems(sessionId) { list -> list.map { if (it.id == message.id) message.copy(status = DeliveryStatus.Sent) else it } }
            setPhase(sessionId, RunPhase.Working)
            delay(replyDelayMs)
            if (NEEDS_APPROVAL.any { text.contains(it) }) {
                editItems(sessionId) {
                    it + ChatItem.Approval(
                        newId("a"), clock(),
                        summary = "执行一条会修改系统的命令",
                        command = "sudo systemctl restart hermes-gateway",
                    )
                }
                setPhase(sessionId, RunPhase.AwaitingInput)
            } else {
                reply(sessionId, placeholderReply(text, images.size, quote))
            }
        }
    }

    override fun retryMessage(sessionId: String, itemId: String) {
        val failed = itemsOf(sessionId).firstOrNull { it.id == itemId } as? ChatItem.Message ?: return
        editItems(sessionId) { list -> list.filterNot { it.id == itemId } }
        sendMessage(sessionId, failed.text, failed.images, failed.quote)
    }

    override fun regenerate(sessionId: String, itemId: String) {
        editItems(sessionId) { list -> list.filterNot { it.id == itemId } }
        startRun(sessionId) {
            setPhase(sessionId, RunPhase.Working)
            delay(1400)
            reply(sessionId, "（占位）这是重新生成的回复。真实实现会调用 Hermes 重新回答上一条消息。")
        }
    }

    override fun deleteItem(sessionId: String, itemId: String) = editItems(sessionId) { list -> list.filterNot { it.id == itemId } }

    override fun stop(sessionId: String) {
        runningJobs.remove(sessionId)?.cancel()
        streaming.update { it + (sessionId to null) }
        setPhase(sessionId, RunPhase.Idle)
    }

    override fun resolveApproval(sessionId: String, itemId: String, choice: String) {
        editItems(sessionId) { list -> list.map { if (it.id == itemId && it is ChatItem.Approval) it.copy(resolvedChoice = choice) else it } }
        startRun(sessionId) {
            setPhase(sessionId, RunPhase.Working)
            delay(1000)
            reply(sessionId, if (choice == "拒绝") "好的，这一步我不执行了。还需要我做别的吗？" else "已经执行完成，服务重启后运行正常。")
        }
    }

    override fun answerClarify(sessionId: String, itemId: String, answer: String) {
        editItems(sessionId) { list -> list.map { if (it.id == itemId && it is ChatItem.Clarify) it.copy(answer = answer) else it } }
        startRun(sessionId) {
            setPhase(sessionId, RunPhase.Working)
            delay(1100)
            reply(sessionId, "已经把 `$answer` 加入 admin 组，现在可以正常访问共享目录了。")
        }
    }

    private fun startRun(sessionId: String, block: suspend () -> Unit) {
        runningJobs.remove(sessionId)?.cancel()
        runningJobs[sessionId] = scope.launch { block() }
    }

    private suspend fun reply(sessionId: String, text: String) {
        if (settings.value.streamReplies) {
            for (end in 1..text.length step 3) {
                streaming.update { it + (sessionId to text.take(end)) }
                delay(24)
            }
            streaming.update { it + (sessionId to null) }
        }
        editItems(sessionId) { it + ChatItem.Message(newId("m"), clock(), Role.Assistant, text) }
        updateSession(sessionId) { it.copy(preview = text.lineSequence().first().take(40), updatedAt = clock()) }
        setPhase(sessionId, RunPhase.Idle)
    }

    private fun placeholderReply(text: String, imageCount: Int, quote: Quote?) = buildString {
        if (quote != null) append("（占位回复）关于你引用的「${quote.text.lineSequence().first().take(24)}」——\n\n")
        append("（占位回复）收到")
        if (imageCount > 0) append(" $imageCount 张图片和")
        append("你的消息：\n\n> ${text.ifBlank { "[图片]" }.take(60)}\n\n")
        append("接入真实 Hermes 后，这里会显示它的**最终回答**——思考过程和工具调用都不会出现在聊天里。")
    }

    // ---- 模型与能力 ----

    private val _models = MutableStateFlow(SampleData.models)
    override val models: StateFlow<List<ModelOption>> = _models.asStateFlow()

    private val _defaultModelId = MutableStateFlow(SampleData.models.first().id)
    override val defaultModelId: StateFlow<String> = _defaultModelId.asStateFlow()

    private fun currentModelName() = _models.value.firstOrNull { it.id == _defaultModelId.value }?.name

    override fun setSessionModel(sessionId: String, modelId: String) {
        val name = _models.value.firstOrNull { it.id == modelId }?.name ?: return
        updateSession(sessionId) { it.copy(model = name) }
    }

    override fun setDefaultModel(modelId: String) {
        _defaultModelId.value = modelId
    }

    private val _skills = MutableStateFlow(SampleData.skills)
    override val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    private val _toolsets = MutableStateFlow(SampleData.toolsets)
    override val toolsets: StateFlow<List<Toolset>> = _toolsets.asStateFlow()

    override fun toggleToolset(name: String) =
        _toolsets.update { list -> list.map { if (it.name == name) it.copy(enabled = !it.enabled) else it } }

    // ---- 定时任务 ----

    private val _jobs = MutableStateFlow(SampleData.jobs())
    override val jobs: StateFlow<List<CronJob>> = _jobs.asStateFlow()

    override fun setJobEnabled(id: String, enabled: Boolean) =
        _jobs.update { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }

    override fun runJobNow(id: String) {
        scope.launch {
            delay(1500)
            _jobs.update { list -> list.map { if (it.id == id) it.copy(lastResult = JobRunResult.Success, lastRunLabel = "刚刚") else it } }
        }
    }

    override fun saveJob(job: CronJob) = _jobs.update { list ->
        if (list.any { it.id == job.id }) list.map { if (it.id == job.id) job else it } else list + job
    }

    override fun deleteJob(id: String) = _jobs.update { list -> list.filterNot { it.id == id } }

    // ---- 设置 ----

    private val _settings = MutableStateFlow(AppSettings())
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    override fun updateSettings(transform: (AppSettings) -> AppSettings) = _settings.update(transform)

    override val errors: Flow<String> = kotlinx.coroutines.flow.emptyFlow()

    private companion object {
        val NEEDS_APPROVAL = listOf("删除", "重启", "清理", "rm ")
    }
}
