package com.hermes.mobile

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.getSystemService
import com.hermes.mobile.data.remote.RemoteHermesRepository
import com.hermes.mobile.platform.AttachmentImporter
import com.hermes.mobile.platform.FileLocalStore
import com.hermes.mobile.platform.mediaDirectory
import com.hermes.mobile.platform.KeystoreSecretBox
import com.hermes.mobile.platform.ReplyNotifier
import com.hermes.mobile.platform.RunService
import com.hermes.mobile.data.store.CrashLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * 仓库跟着进程活，而不是跟着界面：退回桌面、界面被系统回收后，正在等的回复照样能收到。
 * 有回复在等时 [RunService] 让进程保持在前台。
 */
class HermesApplication : Application() {
    /** 后台里没接住的异常：记下来、提示一句，不让整个 App 崩掉。 */
    private val unexpected = CoroutineExceptionHandler { _, error ->
        CrashLog.record(error, fatal = false, version = appVersionName())
        repository.reportUnexpected(error)
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + unexpected)

    val notificationAssistant by lazy {
        com.hermes.mobile.data.notifications.NotificationAssistant(
            store = com.hermes.mobile.platform.NotificationFileStore(this),
            changed = { com.hermes.mobile.platform.NotificationScheduling.configure(this, it) },
            completed = { com.hermes.mobile.platform.notifyNotificationSummary(this) },
            sender = { batch, checkpoint -> repository.sendNotificationSummary(batch, checkpoint) },
        )
    }

    val repository: RemoteHermesRepository by lazy {
        RemoteHermesRepository(
            scope = appScope,
            store = FileLocalStore(this),
            secrets = KeystoreSecretBox(),
            mediaDir = mediaDirectory(this),
            cacheDir = java.io.File(filesDir, "chat-cache"),
            localFileExporter = { path, name, mime -> AttachmentImporter.exportForTermux(this, path, name, mime) },
            notificationAssistant = notificationAssistant,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // 闪退时把出错的地方记下来，下次打开给用户看
        CrashLog.file = java.io.File(filesDir, "crash-log.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            CrashLog.record(error, fatal = true, thread = thread.name, version = appVersionName())
            previous?.uncaughtException(thread, error)
        }
        // Android 自带的 BouncyCastle（名字也叫 "BC"）是阉割版，没有 Ed25519 等 SSH 要用的算法：换成打包进来的完整版
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())
        com.hermes.mobile.platform.NotificationScheduling.configure(this, notificationAssistant.state.value.config)
        val notifier = ReplyNotifier(this)
        appScope.launch { repository.notices.collect(notifier::show) }
        appScope.launch {
            repository.activeRuns.collect { runs ->
                if (runs.isEmpty()) RunService.stop(this@HermesApplication) else RunService.start(this@HermesApplication, runs.size)
            }
        }
        // 网络一恢复就重连，不用等退避时间
        getSystemService<ConnectivityManager>()?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                appScope.launch { repository.onNetworkAvailable() }
            }
        })
    }

    private fun appVersionName(): String? = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
}
