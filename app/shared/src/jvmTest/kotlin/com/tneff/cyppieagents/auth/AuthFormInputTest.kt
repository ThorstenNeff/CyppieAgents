package com.tneff.cyppieagents.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

private const val DESKTOP = "desktop.marker"

@Composable
private fun DesktopMarker() {
    Box(Modifier.fillMaxSize().testTag(DESKTOP))
}

/**
 * CYP-176 keystroke-level paths that the state-machine test can't reach by driving the VM directly:
 * the login form's field→submit→desktop wiring, and the Register client-side validation (§3.2 —
 * empty→disabled, email shape, password mismatch → the single `auth.register.error` node). These are the
 * only tests that simulate typing (the codebase idiom otherwise drives the VM); on-device typing is
 * covered by Maestro.
 */
@OptIn(ExperimentalTestApi::class)
class AuthFormInputTest {

    @Test
    fun login_typeCredentials_enablesSubmit_click_mountsDesktop() = runComposeUiTest {
        val vm = AuthViewModel(StubAuthRepository(loginResult = { _, _ -> LoginResult.Verified() }))
        setContent { MaterialTheme { AuthGate(vm) { DesktopMarker() } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.LOGIN_FORM).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag(AuthTags.LOGIN_SUBMIT).assertIsNotEnabled()
        onNodeWithTag(AuthTags.LOGIN_EMAIL).performTextInput("user@example.com")
        onNodeWithTag(AuthTags.LOGIN_PASSWORD).performTextInput("hunter2")
        waitForIdle()
        onNodeWithTag(AuthTags.LOGIN_SUBMIT).assertIsEnabled()

        onNodeWithTag(AuthTags.LOGIN_SUBMIT).performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(DESKTOP).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun register_passwordMismatch_showsError_submitDisabled() = runComposeUiTest {
        val vm = AuthViewModel(StubAuthRepository())
        setContent { MaterialTheme { AuthGate(vm) { DesktopMarker() } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.LOGIN_FORM).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AuthTags.LOGIN_TO_REGISTER).performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.REGISTER_FORM).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag(AuthTags.REGISTER_EMAIL).performTextInput("new@example.com")
        onNodeWithTag(AuthTags.REGISTER_PASSWORD).performTextInput("hunter2")
        onNodeWithTag(AuthTags.REGISTER_PASSWORD_CONFIRM).performTextInput("hunterTYPO")
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.REGISTER_ERROR).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AuthTags.REGISTER_SUBMIT).assertIsNotEnabled()
    }

    @Test
    fun register_invalidEmail_showsError_submitDisabled() = runComposeUiTest {
        val vm = AuthViewModel(StubAuthRepository())
        setContent { MaterialTheme { AuthGate(vm) { DesktopMarker() } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.LOGIN_FORM).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AuthTags.LOGIN_TO_REGISTER).performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.REGISTER_FORM).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag(AuthTags.REGISTER_EMAIL).performTextInput("notanemail")
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.REGISTER_ERROR).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AuthTags.REGISTER_SUBMIT).assertIsNotEnabled()
    }
}
