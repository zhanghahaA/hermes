package com.hermes.mobile.data.notifications

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.mobile.platform.NotificationFileStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 需在 Android 设备上执行：验证真实 Keystore 和原子文件，桌面替身无法覆盖。 */
@RunWith(AndroidJUnit4::class)
class NotificationStorageTest {
    @Test fun encryptedStoreSurvivesRecreationAndClearRemovesRecords() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(app.cacheDir, "notification-test-" + UUID.randomUUID()).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = dir
        }
        try {
            val store = NotificationFileStore(context)
            val row = NotificationRecord("one", "hash", "example.delivery", "快递", "测试标题", "private-notification-body", 1)
            val state = NotificationState(records = listOf(row), lastSummary = "private-summary-body")
            store.save(state)
            val encrypted = File(dir, "notification-assistant.enc").readText()
            assertFalse(encrypted.contains("private-notification-body"))
            assertFalse(encrypted.contains("private-summary-body"))
            assertEquals(state, NotificationFileStore(context).load())
            store.save(NotificationState())
            assertTrue(NotificationFileStore(context).load()!!.records.isEmpty())
        } finally { dir.deleteRecursively() }
    }
}
