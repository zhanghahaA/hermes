package com.hermes.mobile.platform

import com.hermes.mobile.data.store.LocalStore
import com.hermes.mobile.data.store.SecretBox
import com.hermes.mobile.data.store.StoredState

// app 里 AndroidStorage.kt 的桌面替身：只在内存里存，测试用。

class MemoryLocalStore(var state: StoredState? = null) : LocalStore {
    override fun load(): StoredState? = state
    override fun save(state: StoredState) {
        this.state = state
    }
}

/** 测试用的“加密”：只做可逆变换，确认仓库确实只存密文。 */
class FakeSecretBox : SecretBox {
    override fun encrypt(plain: String): String = "enc:" + plain.reversed()
    override fun decrypt(cipher: String): String? = cipher.removePrefix("enc:").reversed()
}
