package com.hermes.mobile.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.hermes.mobile.MainActivity
import com.hermes.mobile.data.model.ChatNotice

/** App 不在前台时，回复到了、需要审批就发通知；点通知直接打开那个对话。 */
class ReplyNotifier(private val context: Context) {

    fun show(notice: ChatNotice) {
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (foreground) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!settingsAllow()) return
        ensureChannels(context)
        val title = when (notice.kind) {
            ChatNotice.Kind.Reply -> notice.title
            ChatNotice.Kind.NeedsInput -> "${notice.title} · 需要你处理"
            ChatNotice.Kind.Failed -> "${notice.title} · 出错了"
        }
        val notification = NotificationCompat.Builder(context, RepliesChannel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(notice.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openApp(context, notice.sessionId))
            .build()
        NotificationManagerCompat.from(context).notify(notice.sessionId.hashCode(), notification)
    }

    private fun settingsAllow(): Boolean =
        (context.applicationContext as? com.hermes.mobile.HermesApplication)?.repository?.settings?.value?.notifyOnReply != false

    companion object {
        const val RepliesChannel = "replies"
        const val RunningChannel = "running"
        const val ExtraSessionId = "session_id"

        fun ensureChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(NotificationChannel(RepliesChannel, "Hermes 回复", NotificationManager.IMPORTANCE_HIGH))
            manager.createNotificationChannel(
                NotificationChannel(RunningChannel, "正在回复", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }

        fun openApp(context: Context, sessionId: String?): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { if (sessionId != null) putExtra(ExtraSessionId, sessionId) }
            return PendingIntent.getActivity(
                context, sessionId?.hashCode() ?: 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
