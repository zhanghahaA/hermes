package com.hermes.mobile

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.getSystemService
import com.hermes.mobile.data.remote.RemoteHermesRepository
import com.hermes.mobile.platform.FileLocalStore
import com.hermes.mobile.platform.KeystoreSecretBox
import com.hermes.mobile.platform.ReplyNotifier
import com.hermes.mobile.platform.RunService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 仓库跟着进程活，而不是跟着界面：退回桌面、界面被系统回收后，正在等的回复照样能收到。
 * 有回复在等时 [RunService] 让进程保持在前台。
 */
class HermesApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val repository: RemoteHermesRepository by lazy {
        RemoteHermesRepository(scope = appScope, store = FileLocalStore(this), secrets = KeystoreSecretBox())
    }

    override fun onCreate() {
        super.onCreate()
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
}
