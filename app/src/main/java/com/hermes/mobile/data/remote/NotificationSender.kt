package com.hermes.mobile.data.remote

import com.hermes.mobile.data.ConnectionDiagnostics
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.SshAuthMethod
import com.hermes.mobile.data.notifications.NotificationAssistant
import com.hermes.mobile.data.notifications.NotificationBatch
import com.hermes.mobile.data.notifications.NotificationTarget
import com.hermes.mobile.data.ssh.SshAuth
import com.hermes.mobile.data.ssh.SshTunnel
import com.hermes.mobile.data.store.SecretBox
import com.hermes.mobile.data.store.StoredState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive

/** 使用绑定的连接和 Bot 单独发请求，绝不切换界面正在使用的客户端或 SSH 隧道。 */
class NotificationSender(
    private val snapshot: () -> StoredState,
    private val secrets: SecretBox,
    private val factory: (String, String?, String?) -> HermesClient = { url, key, bot -> HermesClient(url, key, bot) },
    private val pollMillis: Long = 2_000,
) {
    suspend fun createSession(target: NotificationTarget): String = withClient(target) { client ->
        client.createSession("通知助手")?.id ?: error("服务器没有返回会话")
    }

    suspend fun send(batch: NotificationBatch, checkpoint: (String) -> Unit): String = withTimeoutOrNull(4 * 60_000L) {
        withClient(batch.target) { client ->
            val run = batch.runId ?: client.startRun(
                batch.target.sessionId, JsonPrimitive(NotificationAssistant.prompt(batch)),
                idempotencyKey = "notifications-" + batch.id, instructions = NotificationAssistant.Instructions,
            ).run_id?.also(checkpoint) ?: error("服务器没有返回任务")
            while (true) {
                val status = client.runStatus(run)
                when (status.status) {
                    "completed" -> return@withClient status.output.orEmpty()
                    "failed", "cancelled", "interrupted" -> error("摘要任务未完成")
                    "waiting_for_approval", "waiting_for_input", "waiting_for_clarify" -> error("需要用户处理")
                }
                if (!status.pending_controls.isNullOrEmpty() || status.approval != null) error("需要用户处理")
                delay(pollMillis)
            }
            @Suppress("UNREACHABLE_CODE")
            ""
        }
    } ?: error("摘要超时，保留批次稍后查询")

    private suspend fun <T> withClient(target: NotificationTarget, block: suspend (HermesClient) -> T): T {
        val state = snapshot()
        val saved = state.connections.firstOrNull { it.id == target.connectionId } ?: error("绑定的连接已删除")
        val baseBot = saved.profile?.takeIf(String::isNotBlank) ?: "default"
        val keyName = if (target.bot == baseBot) saved.id else "bot:" + saved.id + ":" + target.bot
        val key = state.keys[keyName]?.let(secrets::decrypt)
        // 其它 Bot 必须使用它自己的密钥，不能回退到主连接密钥。
        require(target.bot == baseBot || !key.isNullOrBlank()) { "绑定的 Bot 尚未开通访问" }
        val profile = saved.copy(profile = target.bot.takeUnless { it == "default" })
        var tunnel: SshTunnel? = null
        try {
            val client = if (profile.type == ConnectionType.Ssh) {
                require(!profile.sshHostKey.isNullOrBlank()) { "请先在连接设置中验证并保存 SSH 指纹" }
                val secret = state.keys["ssh:" + profile.id]?.let(secrets::decrypt) ?: error("没有 SSH 凭据")
                val auth = if (profile.sshAuth == SshAuthMethod.Key) SshAuth.Key(secret) else SshAuth.Password(secret)
                val trace = ConnectionDiagnostics().begin(ConnectionDiagnostics.Use.Connection)
                val ssh = SshTunnel(profile.host.trim(), profile.sshPort, profile.sshUser ?: error("没有 SSH 用户名"),
                    auth, profile.port, profile.sshHostKey, {}, diagnostics = trace)
                tunnel = ssh
                withContext(Dispatchers.IO) { ssh.connect() }
                factory(ssh.localBaseUrl, key, profile.profile).throughSshTunnel(trace)
            } else {
                val url = when (profile.type) {
                    ConnectionType.Direct -> "http://" + profile.host.trim() + ":" + profile.port
                    ConnectionType.Local -> "http://127.0.0.1:" + profile.port
                    ConnectionType.Https -> profile.host.trim().let { if (it.startsWith("https://") || it.startsWith("http://")) it else "https://" + it }
                    ConnectionType.Ssh -> error("SSH 连接需要隧道")
                }
                factory(url, key, profile.profile)
            }
            return block(client)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { tunnel?.close() }
        }
    }
}
