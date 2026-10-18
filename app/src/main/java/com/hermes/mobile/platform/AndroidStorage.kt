package com.hermes.mobile.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import com.hermes.mobile.data.store.LocalStore
import com.hermes.mobile.data.store.SecretBox
import com.hermes.mobile.data.store.StoredState
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 状态存成 App 私有目录里的一个 JSON 文件，原子写入，写到一半崩溃也不会坏。 */
class FileLocalStore(context: Context) : LocalStore {
    private val file = AtomicFile(File(context.filesDir, "hermes-state.json"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun load(): StoredState? = runCatching {
        json.decodeFromString(StoredState.serializer(), file.readFully().decodeToString())
    }.getOrNull()

    override fun save(state: StoredState) {
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(StoredState.serializer(), state).encodeToByteArray())
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
        }
    }
}

/** 用 Android Keystore 里的 AES-256-GCM 密钥加密 API Key；密钥不出硬件，卸载 App 即失效。 */
class KeystoreSecretBox : SecretBox {
    private val alias = "hermes-api-keys"

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.iv + cipher.doFinal(plain.encodeToByteArray())
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    override fun decrypt(cipher: String): String? = runCatching {
        val data = Base64.decode(cipher, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        c.doFinal(data, 12, data.size - 12).decodeToString()
    }.getOrNull()
}
