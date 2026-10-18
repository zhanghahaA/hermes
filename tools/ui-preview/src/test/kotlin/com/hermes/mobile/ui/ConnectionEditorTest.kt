package com.hermes.mobile.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.hermes.mobile.data.fake.FakeHermesRepository
import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionTestStep
import com.hermes.mobile.data.model.SshAuthMethod
import com.hermes.mobile.ui.settings.ConnectionEditorScreen
import com.hermes.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ConnectionEditorTest {

    /** 记下保存时传给仓库的东西。 */
    private class RecordingRepo(scope: CoroutineScope) : com.hermes.mobile.data.HermesRepository by FakeHermesRepository(scope) {
        var saved: Triple<ConnectionProfile, String?, String?>? = null
        override fun saveConnection(profile: ConnectionProfile, apiKey: String?, sshSecret: String?) {
            saved = Triple(profile, apiKey, sshSecret)
        }
        override fun testConnection(profile: ConnectionProfile, apiKey: String?, sshSecret: String?): Flow<List<ConnectionTestStep>> =
            kotlinx.coroutines.flow.emptyFlow()
    }

    @Test
    fun generatedKeyIsShownAndSaved() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = RecordingRepo(scope)
            setContent {
                HermesTheme {
                    CompositionLocalProvider(LocalShowMessage provides {}) {
                        ConnectionEditorScreen(repo, connectionId = null, initialType = "Ssh", onClose = {})
                    }
                }
            }
            onNodeWithText("在手机上生成新密钥").performScrollTo().performClick()
            onNodeWithText("复制公钥").performScrollTo().assertIsDisplayed()
            onNodeWithText("重新生成密钥").assertIsDisplayed()
            onNodeWithText("保存").performClick()
            val (profile, _, secret) = assertNotNull(repo.saved)
            assertEquals(SshAuthMethod.Key, profile.sshAuth)
            assertTrue(secret!!.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"), "保存时带上新生成的私钥")
        } finally {
            scope.cancel()
        }
    }
}
