package com.hermes.mobile.data.store

import com.hermes.mobile.data.model.AppSettings
import com.hermes.mobile.data.model.ConnectionProfile
import kotlinx.serialization.Serializable

/** 存在手机上的全部状态。API Key 只存密文（[keys]：连接 id → 密文）。 */
@Serializable
data class StoredState(
    val connections: List<ConnectionProfile> = emptyList(),
    val activeConnectionId: String? = null,
    val settings: AppSettings = AppSettings(),
    val keys: Map<String, String> = emptyMap(),
    /** 还没收到结果的对话轮次。App 被杀后重新打开，靠它接着等回复、补上没处理的审批。 */
    val runs: List<StoredRun> = emptyList(),
    /** 会话 id → 没发出去的草稿文字。 */
    val drafts: Map<String, String> = emptyMap(),
    /** 连接 id → 从手机新建对话时默认用的模型（ModelOption.id）。没设就用 Hermes 自己的默认。 */
    val defaultModels: Map<String, String> = emptyMap(),
    /**
     * "连接 id/会话 id" → 这个对话选的模型（ModelOption.id，即 "provider:model"）。
     * Hermes 的 /v1/runs 不看会话上锁定的模型，所以每一轮都要带上。
     */
    val sessionModels: Map<String, String> = emptyMap(),
)

@Serializable
data class StoredRun(val connectionId: String, val sessionId: String, val runId: String)

/** 状态读写。Android 上是 App 私有目录里的一个 JSON 文件。 */
interface LocalStore {
    fun load(): StoredState?
    fun save(state: StoredState)
}

/** 加解密 API Key。Android 上用 Keystore 里的 AES 密钥，密钥本身拿不出来。 */
interface SecretBox {
    fun encrypt(plain: String): String
    fun decrypt(cipher: String): String?
}
