package com.tneff.cyppieagents.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

private const val DESKTOP = "desktop.marker"

/** The desktop stand-in the gate mounts only at [AuthUiState.Verified] — its (non-)presence is the gate. */
@Composable
private fun DesktopMarker() {
    Box(Modifier.fillMaxSize().testTag(DESKTOP)) { Text("desktop") }
}

/**
 * CYP-176 login-gate state machine (auth-spec §2) driven through the real UI: boot probe, the hard
 * verify-gate (§-Ask 2a), login accept/reject/throttle, sub-screen navigation, register→pending,
 * neutral forgot, the reset/verify deep-links, resend, and logout. Field text is driven via the VM's
 * public API (the codebase idiom — no keystroke simulation here; keystroke paths are covered in
 * [AuthFormInputTest] and on-device by Maestro). Every assertion is by testTag (the QA/CYP-7 contract).
 */
@OptIn(ExperimentalTestApi::class)
class AuthGateStateMachineTest {

    private fun gate(stub: StubAuthRepository) = AuthViewModel(stub)

    // --- Boot probe (§5.3) ---

    @Test
    fun boot_verifiedSession_mountsDesktop_notLogin() = runComposeUiTest {
        val vm = gate(StubAuthRepository(sessionState = SessionState.Verified))
        setContent { MaterialTheme { AuthGate(vm) { DesktopMarker() } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(DESKTOP).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AuthTags.LOGIN_FORM).assertDoesNotExist()
    }

    @Test
    fun boot_noSession_showsLogin_notDesktop() = runComposeUiTest {
        val vm = gate(StubAuthRepository(sessionState = SessionState.None))
        setContent { MaterialTheme { AuthGate(vm) { DesktopMarker() } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.LOGIN_FORM).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(DESKTOP).assertDoesNotExist()
        // Submit is disabled with empty fields (§5.5 — no server roundtrip for the obviously incomplete).
        onNodeWithTag(AuthTags.LOGIN_SUBMIT).assertIsNotEnabled()
    }

    @Test
    fun boot_unverifiedSession_hardGate_desktopLocked() = runComposeUiTest {
        // §-Ask 2a: an authenticated-but-unverified user must NEVER see the desktop — only verify/resend/logout.
        val vm = gate(StubAuthRepository(sessionState = SessionState.Unverified("user@example.com")))
        setContent { MaterialTheme { AuthGate(vm) { DesktopMarker() } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.VERIFY_PENDING).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(DESKTOP).assertDoesNotExist()
        onNodeWithTag(AuthTags.VERIFY_EMAIL).assertExists()
        onNodeWithTag(AuthTags.VERIFY_LOGOUT).assertExists()
    }

    // --- Login outcomes (§7.2) ---

    @Test
    fun login_verified_mountsDesktop() = runComposeUiTest {
        val vm = gate(StubAuthRepository(loginResult = { _, _ -> LoginResult.Verified }))
        setContent { MaterialTheme { AuthGate(vm) { DesktopMarker() } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.LOGIN_FORM).fetchSemanticsNodes().isNotEmpty() }
        vm.login("user@example.com", "hunter2")
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(DESKTOP).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun login_rejected_showsGenericError_noEnumeration_noRateLimit() = runComposeUiTest {
        val vm = gate(StubAuthRepository(loginResult = { _, _ -> LoginResult.Rejected }))
        setContent { MaterialTheme { AuthGate(vm) { DesktopMarker() } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.LOGIN_FORM).fetchSemanticsNodes().isNotEmpty() }
        vm.login("user@example.com", "wrong")
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.LOGIN_ERROR).fetchSemanticsNodes().isNotEmpty() }
        // The generic error is not the rate-limit surface, and the desktop stays locked.
        onNodeWithTag(AuthTags.LOGIN_RATE_LIMITED).assertDoesNotExist()
        onNodeWithTag(DESKTOP).assertDoesNotExist()
    }

    @Test
    fun login_rateLimited_showsHonest429_notError() = runComposeUiTest {
        val vm = gate(StubAuthRepository(loginResult = { _, _ -> LoginResult.RateLimited() }))
        setContent { MaterialTheme { AuthGate(vm) { DesktopMarker() } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.LOGIN_FORM).fetchSemanticsNodes().isNotEmpty() }
        vm.login("user@example.com", "hunter2")
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.LOGIN_RATE_LIMITED).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AuthTags.LOGIN_ERROR).assertDoesNotExist()
    }

    @Test
    fun login_unverified_entersHardGate() = runComposeUiTest {
        val vm = gate(StubAuthRepository(loginResult = { _, _ -> LoginResult.Unverified("user@example.com") }))
        setContent { MaterialTheme { AuthGate(vm) { DesktopMarker() } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.LOGIN_FORM).fetchSemanticsNodes().isNotEmpty() }
        vm.login("user@example.com", "hunter2")
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.VERIFY_PENDING).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(DESKTOP).assertDoesNotExist()
    }

    // --- Sub-screen navigation ---

    @Test
    fun navigation_login_register_forgot_roundTrips() = runComposeUiTest {
        val vm = gate(StubAuthRepository())
        setContent { MaterialTheme { AuthGate(vm) { DesktopMarker() } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.LOGIN_FORM).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag(AuthTags.LOGIN_TO_REGISTER).performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.REGISTER_FORM).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag(AuthTags.REGISTER_TO_LOGIN).performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.LOGIN_FORM).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag(AuthTags.LOGIN_TO_FORGOT).performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.FORGOT_FORM).fetchSemanticsNodes().isNotEmpty() }
    }

    // --- Register → neutral pending (hard gate) ---

    @Test
    fun register_success_entersHardGate_notDesktop() = runComposeUiTest {
        val vm = gate(StubAuthRepository())
        setContent { MaterialTheme { AuthGate(vm) { DesktopMarker() } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.LOGIN_FORM).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AuthTags.LOGIN_TO_REGISTER).performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.REGISTER_FORM).fetchSemanticsNodes().isNotEmpty() }
        vm.register("new@example.com", "hunter2")
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.VERIFY_PENDING).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(DESKTOP).assertDoesNotExist()
    }

    // --- Forgot → neutral "sent" (no enumeration) ---

    @Test
    fun forgot_submit_showsNeutralSent() = runComposeUiTest {
        val vm = gate(StubAuthRepository())
        setContent { MaterialTheme { AuthGate(vm) { DesktopMarker() } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.LOGIN_FORM).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AuthTags.LOGIN_TO_FORGOT).performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.FORGOT_FORM).fetchSemanticsNodes().isNotEmpty() }
        vm.requestReset("user@example.com")
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.FORGOT_SENT).fetchSemanticsNodes().isNotEmpty() }
    }

    // --- Reset deep-link (§2.2 / §7.5) ---

    @Test
    fun resetDeepLink_success_showsSuccess() = runComposeUiTest {
        val vm = gate(StubAuthRepository(setNewPasswordResult = { _, _ -> SetPasswordResult.Ok }))
        setContent { MaterialTheme { AuthGate(vm) { DesktopMarker() } } }
        vm.openResetLink("opaque-token")
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.RESET_FORM).fetchSemanticsNodes().isNotEmpty() }
        vm.setNewPassword("brandNewPw")
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.RESET_SUCCESS).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun resetDeepLink_tokenInvalid_showsHonestError() = runComposeUiTest {
        val vm = gate(StubAuthRepository(setNewPasswordResult = { _, _ -> SetPasswordResult.TokenInvalid }))
        setContent { MaterialTheme { AuthGate(vm) { DesktopMarker() } } }
        vm.openResetLink("stale-token")
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.RESET_FORM).fetchSemanticsNodes().isNotEmpty() }
        vm.setNewPassword("brandNewPw")
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.RESET_TOKEN_INVALID).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun register_rateLimited_showsHonest429_atDedicatedNode() = runComposeUiTest {
        val vm = gate(StubAuthRepository(registerResult = { _, _ -> RegisterResult.RateLimited() }))
        setContent { MaterialTheme { AuthGate(vm) { DesktopMarker() } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.LOGIN_FORM).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AuthTags.LOGIN_TO_REGISTER).performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.REGISTER_FORM).fetchSemanticsNodes().isNotEmpty() }
        vm.register("new@example.com", "hunter2")
        // Dedicated amber 429 node (spec refine 1982acb), not the generic error node.
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.REGISTER_RATE_LIMITED).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AuthTags.REGISTER_ERROR).assertDoesNotExist()
    }

    @Test
    fun resetDeepLink_rateLimited_showsHonest429_atDedicatedNode() = runComposeUiTest {
        val vm = gate(StubAuthRepository(setNewPasswordResult = { _, _ -> SetPasswordResult.RateLimited() }))
        setContent { MaterialTheme { AuthGate(vm) { DesktopMarker() } } }
        vm.openResetLink("opaque-token")
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.RESET_FORM).fetchSemanticsNodes().isNotEmpty() }
        vm.setNewPassword("brandNewPw")
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.RESET_RATE_LIMITED).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AuthTags.RESET_ERROR).assertDoesNotExist()
    }

    // --- Verify deep-link (§2.2 / §7.6) ---

    @Test
    fun verifyDeepLink_success_thenContinue_returnsToLogin() = runComposeUiTest {
        val vm = gate(StubAuthRepository(sessionState = SessionState.None, verifyEmailResult = { VerifyResult.Ok }))
        setContent { MaterialTheme { AuthGate(vm) { DesktopMarker() } } }
        vm.openVerifyLink("verify-token")
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.VERIFY_SUCCESS).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AuthTags.VERIFY_CONTINUE).performClick()
        // No session → back to login (fail-closed; not silently into the desktop).
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.LOGIN_FORM).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(DESKTOP).assertDoesNotExist()
    }

    @Test
    fun verifyDeepLink_tokenInvalid_showsHonestError() = runComposeUiTest {
        val vm = gate(StubAuthRepository(verifyEmailResult = { VerifyResult.TokenInvalid }))
        setContent { MaterialTheme { AuthGate(vm) { DesktopMarker() } } }
        vm.openVerifyLink("stale-verify-token")
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.VERIFY_ERROR).fetchSemanticsNodes().isNotEmpty() }
    }

    // --- Hard-gate escapes: resend + logout ---

    @Test
    fun hardGate_resend_showsNeutralResult() = runComposeUiTest {
        val vm = gate(StubAuthRepository(sessionState = SessionState.Unverified("user@example.com")))
        setContent { MaterialTheme { AuthGate(vm) { DesktopMarker() } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.VERIFY_RESEND).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AuthTags.VERIFY_RESEND).performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.VERIFY_RESEND_RESULT).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun hardGate_logout_returnsToLogin() = runComposeUiTest {
        val vm = gate(StubAuthRepository(sessionState = SessionState.Unverified("user@example.com")))
        setContent { MaterialTheme { AuthGate(vm) { DesktopMarker() } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.VERIFY_LOGOUT).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AuthTags.VERIFY_LOGOUT).performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.LOGIN_FORM).fetchSemanticsNodes().isNotEmpty() }
    }
}
