package com.tneff.cyppieagents.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * CYP-278 — the register-path verify gate shows a dedicated, account-enumeration-SAFE notice. The core security
 * property: a fresh registration and an email collision are the SAME `RegisterResult.Pending`, both map to
 * `AuthedUnverified(fromRegister = true)`, so the render is byte-identical between them — it can never leak
 * whether the email exists. UIUX non-negotiable: the affordance line (sign-in + reset) is the SOLE carrier of
 * the existing-user help and MUST be present on every register outcome. The login/session-unverified path keeps
 * the existing verify copy with NO affordance line (anti-divergence).
 *
 * Mutation proof: drop the `if (state.fromRegister)` affordance block → the affordance-present teeth RED; set
 * `fromRegister` false in register() → the register notice falls back to the verify copy (affordances absent) RED;
 * show the affordance unconditionally → [authedUnverified_keepsVerifyCopy_noAffordanceLine] REDs.
 */
@OptIn(ExperimentalTestApi::class)
class RegisterCollisionNoticeTest {

    private companion object { const val DESKTOP = "desktop.marker" }

    @Composable
    private fun DesktopMarker() {
        Box(Modifier.fillMaxSize().testTag(DESKTOP))
    }

    /** Drive the UI to the register-path verify gate for [email] (register success AND collision are identical). */
    private fun ComposeUiTest.registerAndReachNotice(email: String) {
        val vm = AuthViewModel(StubAuthRepository()) // default registerResult → Pending for ANY email
        setContent { MaterialTheme { AuthGate(vm) { DesktopMarker() } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.LOGIN_FORM).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AuthTags.LOGIN_TO_REGISTER).performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.REGISTER_FORM).fetchSemanticsNodes().isNotEmpty() }
        vm.register(email, "hunter2")
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.VERIFY_PENDING).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.assertRegisterNoticeWithAffordances() {
        onNodeWithTag(AuthTags.VERIFY_EMAIL).assertExists() // the neutral body
        // UIUX non-negotiable: BOTH affordance links present (the sole carrier of the existing-user help).
        onNodeWithTag(AuthTags.VERIFY_TO_LOGIN).assertExists()
        onNodeWithTag(AuthTags.VERIFY_TO_FORGOT).assertExists()
        onNodeWithTag(DESKTOP).assertDoesNotExist() // still a hard gate
    }

    @Test
    fun register_freshEmail_showsNotice_withBothAffordances() = runComposeUiTest {
        registerAndReachNotice("brand-new@example.com")
        assertRegisterNoticeWithAffordances()
    }

    @Test
    fun register_collidingEmail_rendersIdentically_withBothAffordances() = runComposeUiTest {
        // A "colliding" (existing) email is the SAME RegisterResult.Pending → the SAME render as a fresh one.
        // Identical assertions to the fresh case ⇒ the screen is an enumeration no-op (no existence oracle).
        registerAndReachNotice("already-exists@example.com")
        assertRegisterNoticeWithAffordances()
    }

    @Test
    fun authedUnverified_keepsVerifyCopy_noAffordanceLine() = runComposeUiTest {
        // A login/session that is unverified reaches the SAME hard gate but fromRegister=false → the existing
        // verify copy, and the register-only affordance line is ABSENT (anti-divergence: the shared copy is
        // never rewritten, and "already have an account?" would be wrong for a logged-in-unverified user).
        val vm = AuthViewModel(StubAuthRepository(sessionState = SessionState.Unverified("mid-verify@example.com")))
        setContent { MaterialTheme { AuthGate(vm) { DesktopMarker() } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AuthTags.VERIFY_PENDING).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AuthTags.VERIFY_EMAIL).assertExists()
        onNodeWithTag(AuthTags.VERIFY_TO_LOGIN).assertDoesNotExist()
        onNodeWithTag(AuthTags.VERIFY_TO_FORGOT).assertDoesNotExist()
    }
}
