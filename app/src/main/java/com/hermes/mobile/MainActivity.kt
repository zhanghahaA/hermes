package com.hermes.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.fake.FakeHermesRepository
import com.hermes.mobile.ui.HermesApp

/** 骨架阶段让假仓库跟着 ViewModel 活，转屏不丢状态。接入真实实现后改用依赖注入。 */
class AppViewModel : ViewModel() {
    val repository: HermesRepository = FakeHermesRepository(viewModelScope)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val app: AppViewModel = viewModel()
            HermesApp(app.repository)
        }
    }
}
