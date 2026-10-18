package com.hermes.mobile.data

import com.hermes.mobile.data.model.AppSettings
import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionState
import com.hermes.mobile.data.model.ConnectionTestStep
import com.hermes.mobile.data.model.CronJob
import com.hermes.mobile.data.model.ImageRef
import com.hermes.mobile.data.model.ModelOption
import com.hermes.mobile.data.model.Quote
import com.hermes.mobile.data.model.RunPhase
import com.hermes.mobile.data.model.Session
import com.hermes.mobile.data.model.Skill
import com.hermes.mobile.data.model.Toolset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * UI 只依赖这个接口。骨架阶段由 [com.hermes.mobile.data.fake.FakeHermesRepository] 提供假数据，
 * 之后换成真正走 Hermes API Server 的实现即可，界面代码不用改。
 */
interface HermesRepository {
    // 连接
    val connections: StateFlow<List<ConnectionProfile>>
    val activeConnectionId: StateFlow<String?>
    val connectionState: StateFlow<ConnectionState>
    fun switchConnection(id: String)
    fun saveConnection(profile: ConnectionProfile)
    fun deleteConnection(id: String)
    fun testConnection(profile: ConnectionProfile): Flow<List<ConnectionTestStep>>

    // 会话
    val sessions: StateFlow<List<Session>>
    fun session(id: String): Flow<Session?>
    suspend fun refreshSessions()
    fun createSession(): String
    fun renameSession(id: String, title: String)
    fun deleteSession(id: String)
    fun restoreSession(session: Session)
    fun togglePin(id: String)
    fun forkSession(id: String, fromItemId: String?): String

    // 聊天
    fun chatItems(sessionId: String): Flow<List<ChatItem>>
    fun runPhase(sessionId: String): Flow<RunPhase>
    fun streamingText(sessionId: String): Flow<String?>
    /**
     * 发消息。带 [quote] 时，真实实现会把引用内容以 Markdown 引用块（"> ..."）放在正文前面发给 Hermes，
     * 让它知道你在追问哪一段。
     */
    fun sendMessage(sessionId: String, text: String, images: List<ImageRef>, quote: Quote? = null)
    fun retryMessage(sessionId: String, itemId: String)
    fun regenerate(sessionId: String, itemId: String)
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
    val toolsets: StateFlow<List<Toolset>>
    fun toggleToolset(name: String)

    // 定时任务
    val jobs: StateFlow<List<CronJob>>
    fun setJobEnabled(id: String, enabled: Boolean)
    fun runJobNow(id: String)
    fun saveJob(job: CronJob)
    fun deleteJob(id: String)

    // 设置
    val settings: StateFlow<AppSettings>
    fun updateSettings(transform: (AppSettings) -> AppSettings)
}
