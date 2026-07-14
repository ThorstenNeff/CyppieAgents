package com.tneff.cyppieagents.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.net.hub.operator.ui.OperatorAuthTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * CYP-474 — the §4 native-loopback handoff renders **user-visibly** (not just KDoc): the "Weiter im Browser …"
 * copy shows, the OS browser is opened (H3, no webview), and the loopback return listener is armed — while the
 * web-redirect flavor does NOT appear.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp474NativeOidcLoopbackRenderTest {

    @Composable
    private fun Marker() {
        Box(Modifier.testTag("desktop.marker"))
    }

    @Test
    fun nativeHandoff_showsHandoffCopy_opensBrowser_armsLoopback() = runComposeUiTest {
        val stub = StubAuthRepository(githubStartResult = { GithubStart.Redirect("https://gh.test/a") })
        val vm = AuthViewModel(stub, nativeOidcLoopback = true)
        var opened: String? = null
        var armedReturn: ((String?, String?) -> Unit)? = null
        setContent {
            MaterialTheme {
                AuthGate(vm, onOpenExternalUrl = { opened = it }, onAwaitLoopbackReturn = { armedReturn = it }) { Marker() }
            }
        }
        onNodeWithTag(AuthTags.LOGIN_GITHUB, useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(OperatorAuthTags.LOGIN_BROWSER_HANDOFF, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // §4 handoff copy user-visible; the web-redirect flavor is NOT shown (this is the native fork).
        onNodeWithTag(OperatorAuthTags.LOGIN_BROWSER_HANDOFF, useUnmergedTree = true).assertExists()
        onNodeWithTag(AuthTags.GITHUB_REDIRECTING, useUnmergedTree = true).assertDoesNotExist()
        assertEquals("https://gh.test/a", opened, "H3: opened the OS system browser (no embedded webview)")
        assertNotNull(armedReturn, "armed the RFC-8252 loopback return listener")
    }
}
