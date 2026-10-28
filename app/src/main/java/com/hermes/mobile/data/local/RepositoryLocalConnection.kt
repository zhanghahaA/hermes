package com.hermes.mobile.data.local

import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.TestStepStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.first
import com.hermes.mobile.data.model.ConnectionState
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout

/** 只有明确的本机操作会创建或切换此连接，不修改用户的远程连接。 */
class RepositoryLocalConnection(private val repository: () -> HermesRepository,
                                private val startupTimeoutMillis: Long, private val retryDelayMillis: Long) : LocalConnectionAccess {
    constructor(repository: () -> HermesRepository) : this(repository, 90_000, 1500)

    override fun save(apiKey: String) {
        repository().saveConnectionWithoutActivating(ConnectionProfile(LocalRuntimeManager.ConnectionId, "这台手机", ConnectionType.Local, "127.0.0.1"), apiKey)
    }

    override suspend fun awaitActivated(): Unit = withTimeout(45_000) {
        val repo = repository()
        val result = repo.connectionState.first { status ->
            check(repo.activeConnectionId.value == LocalRuntimeManager.ConnectionId) { "连接已切换，请按需重新连接本机" }
            status is ConnectionState.Connected || status is ConnectionState.Failed
        }
        check(result is ConnectionState.Connected) { "本机连接未就绪，请返回运行中心查看状态" }
    }

    override suspend fun verifyAndActivate() {
        val repo = repository()
        val previousConnection = repo.activeConnectionId.value
        val profile = repo.connections.value.firstOrNull { it.id == LocalRuntimeManager.ConnectionId }
            ?: throw RuntimeCommandException(RuntimeIssue.ModelSetupRequired)
        val ready = withTimeoutOrNull(startupTimeoutMillis) {
            while (true) {
                check(repo.activeConnectionId.value == previousConnection) { "连接已切换，本机检查已停止" }
                val steps = repo.testConnection(profile, null).last()
                val failure = steps.firstOrNull { it.status == TestStepStatus.Failed }
                // 这些结果不能靠等待解决；只显示固定原因，不回显接口响应或凭据。
                val detail = failure?.detail.orEmpty()
                when {
                    failure?.title == "API Key 鉴权" && (detail.contains("API Key 不对") || detail.contains("还没填 API Key") ||
                        Regex("HTTP (401|403)\\b").containsMatchIn(detail)) -> throw RuntimeCommandException(RuntimeIssue.LocalApiUnauthorized)
                    failure?.title == "能力探测" || failure?.title == "识别 Hermes" &&
                        (detail.contains("不是 Hermes") || detail.contains("没有 /health") || detail.contains("对方自称")) ->
                        throw RuntimeCommandException(RuntimeIssue.LocalApiUnsupported)
                }
                if (steps.any { it.status == TestStepStatus.Passed } && steps.none {
                        it.status in setOf(TestStepStatus.Failed, TestStepStatus.Pending, TestStepStatus.Running)
                    }) {
                    check(repo.activeConnectionId.value == previousConnection) { "连接已切换，本机已准备好，请按需重新连接" }
                    repo.switchConnection(profile.id)
                    return@withTimeoutOrNull true
                }
                delay(retryDelayMillis)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        if (ready != true) throw RuntimeCommandException(RuntimeIssue.LocalApiNotReady)
    }
}
