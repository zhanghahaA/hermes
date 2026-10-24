package com.hermes.mobile.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.AtomicFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hermes.mobile.HermesApplication
import com.hermes.mobile.data.notifications.IncomingNotification
import com.hermes.mobile.data.notifications.NotificationConfig
import com.hermes.mobile.data.notifications.NotificationState
import com.hermes.mobile.data.notifications.NotificationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.File

/** 通知正文以 Keystore 密文原子保存，既不进通用诊断，也不进备份。 */
class NotificationFileStore(context: Context) : NotificationStore {
    private val file = AtomicFile(File(context.filesDir, "notification-assistant.enc"))
    private val box = KeystoreSecretBox()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    override fun load(): NotificationState? {
        if (!file.baseFile.exists()) return null
        return try {
            val plain = box.decrypt(file.readFully().decodeToString()) ?: error("通知记录无法解密")
            json.decodeFromString(NotificationState.serializer(), plain)
        } catch (_: Exception) {
            NotificationState(status = "本地通知记录无法读取，助手已停用；可清除本地记录后重新配置")
        }
    }
    override fun save(state: NotificationState) {
        val bytes = box.encrypt(json.encodeToString(NotificationState.serializer(), state)).encodeToByteArray()
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }
}

fun notificationAccessGranted(context: Context): Boolean =
    context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

/** 只提示摘要已到达，不把通知原文或连接信息放在锁屏上。点击回 App 查看固定目标。 */
fun notifyNotificationSummary(context: Context) {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("notification-summaries", "通知助手摘要", NotificationManager.IMPORTANCE_DEFAULT))
    runCatching {
        NotificationManagerCompat.from(context).notify(15102,
            NotificationCompat.Builder(context, "notification-summaries")
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("通知助手摘要已完成")
                .setContentText("点此打开 Hermes，前往设置中的通知助手查看")
                .setContentIntent(ReplyNotifier.openApp(context, null))
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build())
    }
}

data class NotificationApp(val packageName: String, val name: String)

@Composable
fun rememberNotificationAccess(): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var granted by remember { mutableStateOf(notificationAccessGranted(context)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = notificationAccessGranted(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return granted to {
        runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }
}

/** 仅查询有启动入口的应用，不申请读取全部软件列表。 */
@Composable
fun rememberNotificationApps(): List<NotificationApp> {
    val context = LocalContext.current
    return remember {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        context.packageManager.queryIntentActivities(intent, 0)
            .map { NotificationApp(it.activityInfo.packageName, it.loadLabel(context.packageManager).toString()) }
            .filter { it.packageName != context.packageName }.distinctBy { it.packageName }.sortedBy { it.name }
    }
}

/** Android 负责绑定与唤起；不把历史活动通知重新采集为新通知。 */
class HermesNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = application as HermesApplication
        val (config, token) = app.notificationAssistant.captureContext()
        if (!config.enabled || sbn.packageName !in config.packages || !notificationAccessGranted(this)) return
        val notification = sbn.notification
        val extras = notification.extras
        val raw = IncomingNotification(
            key = sbn.key, packageName = sbn.packageName,
            appName = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName),
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty(),
            ongoing = sbn.isOngoing,
            groupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            category = notification.category,
        )
        app.appScope.launch(Dispatchers.IO) {
            // 不允许通知正文进入全局异常日志。
            runCatching { app.notificationAssistant.capture(raw, token) }
        }
    }
}

/** 系统周期任务，网络可用时运行；重启后保留，调度可能被省电策略延迟。 */
object NotificationScheduling {
    private const val JobId = 15101
    fun configure(context: Context, config: NotificationConfig) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!config.enabled || config.target == null) {
            scheduler.cancel(JobId)
            return
        }
        val interval = config.intervalMinutes * 60_000L
        if (scheduler.getPendingJob(JobId)?.intervalMillis == interval) return
        scheduler.schedule(JobInfo.Builder(JobId, ComponentName(context, NotificationSummaryJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(interval)
            .setPersisted(true)
            .build())
    }
}

class NotificationSummaryJob : JobService() {
    private var running: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as HermesApplication
        running = app.appScope.launch {
            val granted = notificationAccessGranted(this@NotificationSummaryJob)
            val success = withTimeoutOrNull(5 * 60_000L) {
                try {
                    app.notificationAssistant.summarize(granted)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            } ?: false
            jobFinished(params, !success && granted && app.notificationAssistant.state.value.config.enabled)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return (application as HermesApplication).notificationAssistant.state.value.config.enabled
    }
}
