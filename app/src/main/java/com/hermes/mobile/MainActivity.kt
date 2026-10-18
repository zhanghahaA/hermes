package com.hermes.mobile

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.remote.RemoteHermesRepository
import com.hermes.mobile.platform.FileLocalStore
import com.hermes.mobile.platform.KeystoreSecretBox
import com.hermes.mobile.ui.HermesApp
import com.hermes.mobile.ui.MainRoute
import com.hermes.mobile.ui.WelcomeRoute
import com.hermes.mobile.ui.navigation.Navigator

/** 仓库和页面栈跟着 ViewModel 活，转屏不丢状态。 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    val repository: HermesRepository = RemoteHermesRepository(
        scope = viewModelScope,
        store = FileLocalStore(application),
        secrets = KeystoreSecretBox(),
    )
    val navigator = Navigator(MainRoute).apply {
        // 第一次打开、还没有任何连接：先进欢迎页选择 Hermes 在哪里
        if (repository.connections.value.isEmpty()) push(WelcomeRoute)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val app: AppViewModel = viewModel()
            HermesApp(app.repository, app.navigator)
        }
    }
}
