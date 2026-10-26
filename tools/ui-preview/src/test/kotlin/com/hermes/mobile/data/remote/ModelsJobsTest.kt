package com.hermes.mobile.data.remote

import com.hermes.mobile.data.model.AppFeature
import com.hermes.mobile.data.model.ChatNotice
import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionState
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.CronJob
import com.hermes.mobile.data.model.JobRunResult
import com.hermes.mobile.data.model.JobSchedule
import com.hermes.mobile.platform.FakeSecretBox
import com.hermes.mobile.platform.MemoryLocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 阶段 4b：模型切换、定时任务。 */
class ModelsJobsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val servers = mutableListOf<FakeHermesServer>()
    private val mediaDir = kotlin.io.path.createTempDirectory("media").toFile()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        servers.forEach { it.shutdown() }
    }

    private fun server() = FakeHermesServer(FakeHermesServer.Version.Modern).start().also { servers += it }

    private fun connected(s: FakeHermesServer, store: MemoryLocalStore = MemoryLocalStore()): RemoteHermesRepository {
        val repo = RemoteHermesRepository(scope, store, FakeSecretBox(), mediaDir = mediaDir, jobPollMillis = 50, backoff = { 50L })
        if (store.state?.connections.isNullOrEmpty()) {
            repo.saveConnection(ConnectionProfile(id = "c1", name = "测试", type = ConnectionType.Direct, host = s.server.hostName, port = s.server.port), s.key)
        }
        runBlocking { withTimeout(10_000) { repo.connectionState.first { it is ConnectionState.Connected } } }
        runBlocking { withTimeout(5_000) { repo.sessions.first { it.isNotEmpty() } } }
        return repo
    }

    private fun <T> Flow<T>.await(what: String, predicate: (T) -> Boolean): T = runBlocking {
        try {
            withTimeout(10_000) { first(predicate) }
        } catch (e: Exception) {
            throw AssertionError("等不到：$what", e)
        }
    }

    private fun bodyOf(s: FakeHermesServer, method: String, path: String) =
        s.requests.last { it.method == method && it.path == path }.body.clone().readUtf8()

    @Test
    fun modelsComeFromSignedInProviders() {
        val s = server()
        val repo = connected(s)
        val models = repo.models.await("模型清单") { it.isNotEmpty() }
        assertEquals(listOf("custom", "openrouter", "openrouter"), models.map { it.providerSlug }, "没登录的 Provider 不显示，当前的排最前")
        assertEquals("Hermes 默认", models.first().note)
        repo.defaultModelId.await("默认模型") { it == "custom:fake-model" }
        assertTrue(AppFeature.Models in repo.features.value)
    }

    @Test
    fun switchingModelLocksTheSession() {
        val s = server()
        val store = MemoryLocalStore()
        val repo = connected(s, store)
        val sonnet = repo.models.await("模型清单") { it.isNotEmpty() }.first { it.model == "anthropic/claude-sonnet-5.5" }
        repo.setSessionModel("s1", sonnet.id)
        repo.sessions.await("会话显示新模型") { list -> list.first { it.id == "s1" }.model == sonnet.model }
        runBlocking { withTimeout(5_000) { while (s.modelLocks["s1"] == null) kotlinx.coroutines.delay(20) } }
        assertEquals("openrouter/anthropic/claude-sonnet-5.5", s.modelLocks["s1"])

        // Hermes 的 /v1/runs 不读会话锁，每条消息都要带上模型
        repo.sendMessage("s1", "用新模型回答", emptyList())
        runBlocking { withTimeout(10_000) { while (s.requests.none { it.path == "/v1/runs" }) kotlinx.coroutines.delay(20) } }
        val run = bodyOf(s, "POST", "/v1/runs")
        assertTrue("\"model\":\"anthropic/claude-sonnet-5.5\"" in run && "\"provider\":\"openrouter\"" in run, run)

        // 重启 App 后（模型清单还没读到时）也照样带上
        val again = connected(s, store)
        again.sendMessage("s1", "再来一句", emptyList())
        runBlocking { withTimeout(10_000) { while (s.requests.count { it.path == "/v1/runs" } < 2) kotlinx.coroutines.delay(20) } }
        val second = bodyOf(s, "POST", "/v1/runs")
        assertTrue("\"model\":\"anthropic/claude-sonnet-5.5\"" in second, second)
    }

    @Test
    fun modelPickedOnDraftAppliesAfterFirstSend() {
        val s = server()
        val repo = connected(s)
        val gpt = repo.models.await("模型清单") { it.isNotEmpty() }.first { it.model == "openai/gpt-6.1-sol" }
        val draft = assertNotNull(repo.createSession())
        repo.setSessionModel(draft, gpt.id)
        assertEquals(gpt.model, runBlocking { repo.session(draft).first() }?.model, "草稿上先显示选的模型")
        assertTrue(s.modelLocks.isEmpty(), "草稿还没上服务器，不发请求")
        repo.sendMessage(draft, "你好", emptyList())
        runBlocking { withTimeout(10_000) { while (s.modelLocks["new1"] == null) kotlinx.coroutines.delay(20) } }
        assertEquals("openrouter/openai/gpt-6.1-sol", s.modelLocks["new1"])
        runBlocking { withTimeout(10_000) { while (s.requests.none { it.path == "/v1/runs" }) kotlinx.coroutines.delay(20) } }
        val lock = s.requests.indexOfFirst { it.path == "/api/sessions/new1/model" }
        val run = s.requests.indexOfFirst { it.path == "/v1/runs" }
        assertTrue(lock in 0 until run, "先锁模型再发第一条消息")
        val body = bodyOf(s, "POST", "/v1/runs")
        assertTrue("\"model\":\"openai/gpt-6.1-sol\"" in body && "\"provider\":\"openrouter\"" in body, "第一条消息就用选的模型：$body")
    }

    @Test
    fun appDefaultModelAppliesToNewChatsAndSurvivesRestart() {
        val s = server()
        val store = MemoryLocalStore()
        val repo = connected(s, store)
        val sonnet = repo.models.await("模型清单") { it.isNotEmpty() }.first { it.model == "anthropic/claude-sonnet-5.5" }
        repo.setDefaultModel(sonnet.id)
        assertEquals(sonnet.id, repo.defaultModelId.value)

        val again = connected(s, store)
        again.defaultModelId.await("重启后还记得默认模型") { it == sonnet.id }
        val draft = assertNotNull(again.createSession())
        assertEquals(sonnet.model, runBlocking { again.session(draft).first() }?.model)
        again.sendMessage(draft, "嗨", emptyList())
        runBlocking { withTimeout(10_000) { while (s.modelLocks["new1"] == null) kotlinx.coroutines.delay(20) } }
        assertEquals("openrouter/anthropic/claude-sonnet-5.5", s.modelLocks["new1"])
        runBlocking { withTimeout(10_000) { while (s.requests.none { it.path == "/v1/runs" }) kotlinx.coroutines.delay(20) } }
        assertTrue("\"provider\":\"openrouter\"" in bodyOf(s, "POST", "/v1/runs"), "默认模型也要带在每一轮里")

        again.setDefaultModel("custom:fake-model")
        assertFalse(store.state!!.defaultModels.containsKey("c1"), "改回 Hermes 默认就不再单独存")
    }

    private fun newJob(name: String, schedule: JobSchedule) = CronJob(
        id = "", name = name, prompt = "告诉我天气", scheduleLabel = schedule.label(), cron = schedule.toHermes(),
        nextRunLabel = "", lastResult = JobRunResult.Never, lastRunLabel = null, enabled = true, deliverTo = "",
        schedule = schedule,
    )

    @Test
    fun jobsCanBeCreatedPausedAndDeleted() {
        val s = server()
        val repo = connected(s)
        repo.features.await("探测到任务接口") { AppFeature.Jobs in it }
        repo.saveJob(newJob("每日天气", JobSchedule.Weekly(setOf(1, 2, 3, 4, 5), 8, 0)))
        val job = repo.jobs.await("新任务出现") { it.isNotEmpty() }.single()
        assertEquals("0 8 * * 1,2,3,4,5", job.cron)
        assertEquals(JobSchedule.Weekly(setOf(1, 2, 3, 4, 5), 8, 0), job.schedule)
        assertEquals("工作日 08:00", job.scheduleLabel)
        assertTrue(bodyOf(s, "POST", "/api/jobs").contains("\"deliver\":\"local\""))

        repo.setJobEnabled(job.id, false)
        repo.jobs.await("已暂停") { it.single().enabled == false }
        runBlocking { withTimeout(5_000) { while (s.jobs[job.id]?.get("enabled") != false) kotlinx.coroutines.delay(20) } }
        repo.setJobEnabled(job.id, true)
        runBlocking { withTimeout(5_000) { while (s.jobs[job.id]?.get("enabled") != true) kotlinx.coroutines.delay(20) } }

        repo.saveJob(job.copy(name = "天气", cron = "every 2h", schedule = JobSchedule.Interval(120)))
        repo.jobs.await("改名生效") { it.single().name == "天气" && it.single().schedule == JobSchedule.Interval(120) }
        assertTrue(s.requests.any { it.method == "PATCH" && it.path == "/api/jobs/${job.id}" }, "已有任务用 PATCH")

        repo.deleteJob(job.id)
        repo.jobs.await("删除") { it.isEmpty() }
        runBlocking { withTimeout(5_000) { while (s.jobs.isNotEmpty()) kotlinx.coroutines.delay(20) } }
    }

    @Test
    fun badScheduleShowsHermesError() {
        val s = server()
        val repo = connected(s)
        repo.features.await("探测到任务接口") { AppFeature.Jobs in it }
        val error = runBlocking {
            val e = async { withTimeout(5_000) { repo.errors.first() } }
            kotlinx.coroutines.delay(50)
            repo.saveJob(newJob("坏的", JobSchedule.Custom("bad")))
            e.await()
        }
        assertTrue("Invalid schedule" in error, error)
        assertTrue(repo.jobs.value.isEmpty())
    }

    @Test
    fun delayedPauseResumeRepliesCannotRestoreADeletedJob() {
        val s = server()
        val repo = connected(s)
        repo.features.await("探测到任务接口") { AppFeature.Jobs in it }
        repo.saveJob(newJob("test", JobSchedule.Interval(60)))
        val job = repo.jobs.await("新任务") { it.isNotEmpty() }.single()
        s.jobActionDelayMillis = 300
        repo.setJobEnabled(job.id, false)
        repo.setJobEnabled(job.id, true)
        repo.saveJob(job.copy(name = "renamed"))
        repo.deleteJob(job.id)
        runBlocking { withTimeout(5000) {
            while (s.requests.none { it.method == "DELETE" && it.path == "/api/jobs/${job.id}" }) kotlinx.coroutines.delay(20)
        } }
        Thread.sleep(800) // 明确覆盖假服务器延后的两次回包，不能只看乐观删除的瞬时状态。
        assertTrue(s.jobs.isEmpty())
        assertTrue(repo.jobs.value.isEmpty(), "较早的暂停／恢复回包不能把已删任务放回列表")
    }

    @Test
    fun runNowWaitsForTheRunAndNotifies() {
        val s = server()
        val repo = connected(s)
        repo.features.await("探测到任务接口") { AppFeature.Jobs in it }
        repo.saveJob(newJob("每日天气", JobSchedule.Daily(8, 0)))
        val job = repo.jobs.await("新任务") { it.isNotEmpty() }.single()

        val notice: ChatNotice = runBlocking {
            val n = async { withTimeout(10_000) { repo.notices.first() } }
            kotlinx.coroutines.delay(50)
            repo.runJobNow(job.id)
            repo.jobs.await("显示运行中") { it.single().runningNow }
            n.await()
        }
        assertEquals("cron_${job.id}", notice.sessionId)
        assertEquals("每日天气", notice.title)
        assertEquals(ChatNotice.Kind.Reply, notice.kind)
        assertFalse("IMPORTANT" in notice.text, "系统说明不当预览：${notice.text}")
        val done = repo.jobs.await("运行结束") { !it.single().runningNow }.single()
        assertEquals(JobRunResult.Success, done.lastResult)

        val runs = runBlocking { withTimeout(5_000) { repo.jobRuns(done).first { it.isNotEmpty() } } }
        assertEquals(listOf("cron_${job.id}"), runs.map { it.id })
        assertFalse("IMPORTANT" in runs.single().preview, "列表预览也不显示系统说明")
    }
}
