package com.hermes.mobile

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.platform.ReplyNotifier
import com.hermes.mobile.ui.ChatRoute
import com.hermes.mobile.ui.HermesApp
import com.hermes.mobile.ui.MainRoute
import com.hermes.mobile.ui.WelcomeRoute
import com.hermes.mobile.ui.navigation.Navigator

/** 页面栈跟着 ViewModel 活，转屏不丢；仓库在 [HermesApplication] 里，跟着进程活。 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    val repository: HermesRepository = (application as HermesApplication).repository
    val navigator = Navigator(MainRoute).apply {
        // 第一次打开、还没有任何连接：先进欢迎页选择 Hermes 在哪里
        if (repository.connections.value.isEmpty()) push(WelcomeRoute)
    }
}

class MainActivity : ComponentActivity() {
    private val app: AppViewModel by viewModels()

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openFromNotification(intent)
        setContent { HermesApp(app.repository, app.navigator) }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromNotification(intent)
    }

    /** 点了“收到回复”的通知：直接打开那个对话。 */
    private fun openFromNotification(intent: Intent?) {
        val sessionId = intent?.getStringExtra(ReplyNotifier.ExtraSessionId) ?: return
        intent.removeExtra(ReplyNotifier.ExtraSessionId)
        if (app.navigator.current != ChatRoute(sessionId)) app.navigator.push(ChatRoute(sessionId), clearAboveRoot = true)
    }
}
