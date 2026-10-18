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
        val notification = NotificationCompat.Builder(this, ReplyNotifier.RunningChannel)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Hermes 正在回复…")
            .setContentText(if (count > 1) "$count 个对话在进行中" else "回复到了会通知你")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(ReplyNotifier.openApp(this, null))
            .build()
        try {
            ServiceCompat.startForeground(this, NotificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            // 系统不允许此时开前台服务（例如 App 在后台被拉起），就不保活，回复回来后照样能收到
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 起 dataSync 前台服务每天有时长上限，到了就停
        stopSelf()
    }

    companion object {
        private const val NotificationId = 1001
        private const val ExtraCount = "count"
        private var running = false

        fun start(context: Context, count: Int) {
            ReplyNotifier.ensureChannels(context)
            try {
                ContextCompat.startForegroundService(context, Intent(context, RunService::class.java).putExtra(ExtraCount, count))
                running = true
            } catch (e: Exception) {
                // 后台不能启动前台服务（ForegroundServiceStartNotAllowedException），忽略
            }
        }

        fun stop(context: Context) {
            if (!running) return
            running = false
            context.stopService(Intent(context, RunService::class.java))
        }
    }
}
