package com.hermes.mobile.platform

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.hermes.mobile.data.local.*
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

private object RuntimeCallbacks {
    data class Pending(val result: CompletableDeferred<TermuxCommandResult>, val intent: PendingIntent)
    val active = AtomicReference<String?>(null)
    val pending = ConcurrentHashMap<String, Pending>()
    fun finish(id: String, result: TermuxCommandResult) {
        val call = pending.remove(id) ?: return
        active.compareAndSet(id, null)
        call.intent.cancel()
        call.result.complete(result)
    }
}

/** 仅 PendingIntent 持有者可以送达；不导出广播入口，不将回包写日志或磁盘。 */
class RuntimeResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment ?: return
        val result = intent.getBundleExtra("result")
        @Suppress("DEPRECATION")
        val code = (result?.get("exitCode") as? Number)?.toInt()
        @Suppress("DEPRECATION")
        val error = (result?.get("err") as? Number)?.toInt() ?: 0
        RuntimeCallbacks.finish(id, TermuxCommandResult(if (error != 0) -1 else code ?: -1,
            result?.getString("stdout").orEmpty(),
            if (error != 0) "Termux 拒绝执行，请检查外部命令授权和初始化状态" else result?.getString("stderr").orEmpty()))
    }
}

class AndroidTermuxTransport(context: Context) : TermuxTransport {
    private val context = context.applicationContext
    override fun environment(): RuntimeEnvironment {
        val info = runCatching { context.packageManager.getPackageInfo("com.termux", 0) }.getOrNull()
        val version = info?.versionName.orEmpty()
        val supported = supportsTermuxVersion(version)
        @Suppress("DEPRECATION")
        val installer = runCatching { context.packageManager.getInstallerPackageName("com.termux") }.getOrNull()
        val registered = runCatching {
            context.packageManager.getPermissionInfo("com.termux.permission.RUN_COMMAND", 0).packageName == "com.termux"
        }.getOrDefault(false)
        val commandService = runCatching {
            context.packageManager.getServiceInfo(ComponentName("com.termux", "com.termux.app.RunCommandService"), 0)
                .let { it.enabled && it.exported && it.applicationInfo.enabled }
        }.getOrDefault(false)
        return RuntimeEnvironment(
            installed = info != null, compatible = supported && installer != "com.android.vending",
            permission = ContextCompat.checkSelfPermission(context, "com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED,
            arm64 = "arm64-v8a" in Build.SUPPORTED_ABIS,
            freeBytes = StatFs(Environment.getDataDirectory().absolutePath).availableBytes,
            viewerInstalled = runCatching { context.packageManager.getPackageInfo("com.termux.x11", 0) }.isSuccess,
            version = version,
            permissionRegistered = registered,
            commandServiceAvailable = commandService,
            compatibilityIssue = when {
                installer == "com.android.vending" -> "当前为 Google Play 安装来源，本机部署需要 GitHub／F-Droid 兼容版。请先备份 Termux 数据，再按官方说明处理。"
                !supported -> "未识别到兼容的 Termux 版本（${version.ifBlank { "版本未知" }}），需要 0.118 或更新版本。"
                else -> ""
            },
            architecture = Build.SUPPORTED_ABIS.joinToString(" / "),
            installSource = when (installer) { "com.android.vending" -> "Google Play"; "org.fdroid.fdroid" -> "F-Droid"
                null -> "手动安装或来源未登记"; else -> "其他安装来源" },
        )
    }

    override suspend fun run(script: String, input: String): TermuxCommandResult {
        val id = UUID.randomUUID().toString()
        check(RuntimeCallbacks.active.compareAndSet(null, id)) { "上一次 Termux 任务还未返回，请打开 Termux 检查，勿重复安装" }
        val callback = PendingIntent.getBroadcast(context, 0,
            Intent(context, RuntimeResultReceiver::class.java).setData(Uri.parse("hermes-runtime://result/$id")),
            PendingIntent.FLAG_ONE_SHOT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val result = CompletableDeferred<TermuxCommandResult>()
        RuntimeCallbacks.pending[id] = RuntimeCallbacks.Pending(result, callback)
        try {
            val command = Intent("com.termux.RUN_COMMAND")
                .setComponent(ComponentName("com.termux", "com.termux.app.RunCommandService"))
                .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
                .putExtra("com.termux.RUN_COMMAND_STDIN", input)
                .putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback)
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND_CUSTOM_LOG_LEVEL", "0")
            ContextCompat.startForegroundService(context, command)
        } catch (_: Exception) {
            RuntimeCallbacks.finish(id, TermuxCommandResult(-1, stderr = "无法唤起 Termux，请检查安装、执行权限和后台限制"))
        }
        // 取消等待不等于远端命令停止。保留回调和并发锁直到 Termux 真正结束。
        return result.await()
    }

    override fun openViewer() {
        try {
            context.startActivity(Intent().setComponent(ComponentName("com.termux.x11", "com.termux.x11.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { error("浏览器已尝试启动，请从桌面打开 Termux:X11 查看") }
    }
}

@Composable
fun rememberRuntimeControls(refresh: () -> Unit): RuntimeControls {
    val context = LocalContext.current
    val currentRefresh = rememberUpdatedState(refresh)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) currentRefresh.value() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        currentRefresh.value()
        // 系统不再弹窗、或 Termux 晚于本 App 安装时，给出始终可达的手动授权入口。
        if (!granted) runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.packageName)))
        }
    }
    fun open(intent: Intent) { runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    return RuntimeControls(
        requestPermission = { permission.launch("com.termux.permission.RUN_COMMAND") },
        openTermux = { context.packageManager.getLaunchIntentForPackage("com.termux")?.let(::open) },
        downloadTermux = { open(Intent(Intent.ACTION_VIEW, Uri.parse(TermuxRuntimeBackend.DownloadTermux))) },
        downloadViewer = { open(Intent(Intent.ACTION_VIEW, Uri.parse(TermuxRuntimeBackend.DownloadX11))) },
        batterySettings = { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
        permissionSettings = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))) },
        storageSettings = { open(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) },
    )
}
