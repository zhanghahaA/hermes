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
)

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
