package com.hermes.mobile.data.local

import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.TestStepStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withTimeout

/** 只有明确的本机操作会创建或切换此连接，不修改用户的远程连接。 */
class RepositoryLocalConnection(private val repository: () -> HermesRepository) : LocalConnectionAccess {
    override fun save(apiKey: String) {
        repository().saveConnectionWithoutActivating(ConnectionProfile(LocalRuntimeManager.ConnectionId, "这台手机", ConnectionType.Local, "127.0.0.1"), apiKey)
    }

    override suspend fun verifyAndActivate() = withTimeout(45_000) {
        val repo = repository()
        val profile = repo.connections.value.firstOrNull { it.id == LocalRuntimeManager.ConnectionId }
            ?: error("请先配置「这台手机」连接")
        repeat(10) {
            val steps = repo.testConnection(profile, null).last()
            if (steps.any { it.status == TestStepStatus.Passed } && steps.none {
                    it.status in setOf(TestStepStatus.Failed, TestStepStatus.Pending, TestStepStatus.Running)
                }) {
                repo.switchConnection(profile.id)
                return@withTimeout
            }
            delay(1500)
        }
        error("本机接口尚未就绪，请查看 Hermes 日志和模型配置")
    }
}
