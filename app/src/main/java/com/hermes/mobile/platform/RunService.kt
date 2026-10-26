package com.hermes.mobile.platform

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 等 Hermes 回复期间的前台服务：通知栏显示“Hermes 正在回复…”，切到后台也不会断线。
 * 回复全部到了就自己停掉。
 */
class RunService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(ExtraCount, 1) ?: 1
        try {
            ServiceCompat.startForeground(this, NotificationId, notification(this, count), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            foreground = true
        } catch (e: Exception) {
            // 系统不允许此时开前台服务（例如 App 在后台被拉起），就不保活，回复回来后照样能收到
            finishNow()
            return START_NOT_STICKY
        }
        // 进前台之前回复就全到了：现在已经满足了“必须进前台”的要求，可以安全地停了
        if (!wanted) finishNow()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 起 dataSync 前台服务每天有时长上限，到了就停
        finishNow()
    }

    override fun onDestroy() {
        foreground = false
        started = false
        super.onDestroy()
    }

    private fun finishNow() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        started = false
        stopSelf()
    }

    /**
     * 启动、停止都在主线程上调（仓库的 activeRuns 在主线程收集）。
     * 注意：startForegroundService 之后、服务进前台之前 stopService，系统会直接让 App 崩溃
     * （"startForegroundService() did not then call startForeground()"）。审批后任务很快结束时就会这样，
     * 所以只在第一次启动时调 startForegroundService；还没进前台就要停的，交给服务进前台后自己停。
     */
    companion object {
        private const val NotificationId = 1001
        private const val ExtraCount = "count"

        /** 已经调过 startForegroundService、服务还没结束。 */
        @Volatile private var started = false
        /** 服务已经进了前台。 */
        @Volatile private var foreground = false
        /** 现在还需不需要保活。 */
        @Volatile private var wanted = false

        private fun notification(context: Context, count: Int) = NotificationCompat.Builder(context, ReplyNotifier.RunningChannel)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Hermes 正在处理任务…")
            .setContentText(if (count > 1) "$count 项聊天或本机操作在进行中" else "聊天或本机操作正在进行，可回 App 查看")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(ReplyNotifier.openApp(context, null))
            .build()

        fun start(context: Context, count: Int) {
            wanted = true
            ReplyNotifier.ensureChannels(context)
            if (started) {
                // 已经在跑：只更新通知上的数字，不再重新启动服务
                val allowed = android.os.Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (foreground && allowed) {
                    runCatching { androidx.core.app.NotificationManagerCompat.from(context).notify(NotificationId, notification(context, count)) }
                }
                return
            }
            try {
                ContextCompat.startForegroundService(context, Intent(context, RunService::class.java).putExtra(ExtraCount, count))
                started = true
            } catch (e: Exception) {
                // 后台不能启动前台服务（ForegroundServiceStartNotAllowedException），忽略
            }
        }

        fun stop(context: Context) {
            wanted = false
            // 还没进前台：等服务进了前台自己停（见 onStartCommand）
            if (!started || !foreground) return
            started = false
            foreground = false
            context.stopService(Intent(context, RunService::class.java))
        }
    }
}
