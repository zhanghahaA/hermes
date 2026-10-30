package com.hermes.mobile.data.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * App 和一台 Hermes 打交道要用的全部操作。两种实现：
 * - [HermesClient]：Hermes 的 API Server（HTTP，直连 / HTTPS / 本机 Termux）；
 * - [GatewayHermesApi]：SSH 连接，经服务器上的小桥走 Hermes gateway（和电脑版同一个服务，不需要 API Server）。
 *
 * 数据形状统一用 API Server 的（[SessionDto]、[RunStatusDto]、事件流里的 JSON……），gateway 那边在实现里翻译，
 * 聊天逻辑（[RemoteHermesRepository]）只认这一套。
 */
interface HermesApi {
    suspend fun health(): HealthDto
    suspend fun capabilities(): CapabilitiesDto

    suspend fun sessions(limit: Int = 100, offset: Int = 0): SessionListDto
    suspend fun session(id: String): SessionDto?
    suspend fun messages(sessionId: String): MessageListDto
    /** 改标题、置顶、归档（`title` / `pinned` / `archived`）。 */
    suspend fun patchSession(id: String, fields: JsonObject): SessionDto?
    suspend fun deleteSession(id: String)
    suspend fun createSession(title: String?): SessionDto?
    suspend fun forkSession(id: String): SessionDto?

    /** 提交一轮对话。[idempotencyKey] 相同的重试不会让 Hermes 重复执行。 */
    suspend fun startRun(
        sessionId: String,
        input: JsonElement,
        idempotencyKey: String,
        instructions: String? = null,
        model: String? = null,
        provider: String? = null,
        workspace: String? = null,
        reasoningEffort: String? = null,
    ): RunAcceptedDto
    suspend fun runStatus(runId: String): RunStatusDto
    suspend fun respondApproval(runId: String, body: JsonObject)
    suspend fun respondClarify(runId: String, body: JsonObject)
    /** 在 Hermes 回复的过程中插一句话（它会在下一步看到，不打断当前工作）。 */
    suspend fun steerRun(runId: String, text: String)
    suspend fun stopRun(runId: String)
    /**
     * 一轮对话的事件，形状和 API Server 的 SSE 一样（`data` 是 JSON，`event` 字段是事件名）。[afterSeq] 是已经处理过的
     * 最后一个序号，之后的会补发。正常结束时 Flow 完成；网络断开时抛 [HermesError]，由调用方决定是否重连。
     */
    fun runEvents(runId: String, afterSeq: Long): Flow<SseEvent>

    suspend fun modelOptions(): ModelOptionsDto
    /** 把会话锁定到某个模型（之后这个会话的每一轮都用它）。 */
    suspend fun lockSessionModel(sessionId: String, model: String, provider: String)

    /** gateway 的运行状态、各平台连接情况、今天的用量。字段随版本变化，原样返回。 */
    suspend fun healthDetailed(): JsonObject
    suspend fun skills(): SkillListDto
    suspend fun toolsets(): ToolsetListDto

    suspend fun jobs(): JobListDto
    suspend fun createJob(fields: JsonObject): JobDto?
    suspend fun updateJob(id: String, fields: JsonObject): JobDto?
    suspend fun deleteJob(id: String)
    /** pause / resume / run */
    suspend fun jobAction(id: String, action: String): JobDto?
    suspend fun job(id: String): JobDto?

    /** 验证 Key（旧版本没有 /v1/capabilities 时用）。gateway 不用 Key。 */
    suspend fun verifyKey()
    /** 网络变了：丢掉复用中的旧连接。 */
    fun evictIdleConnections() {}
}
