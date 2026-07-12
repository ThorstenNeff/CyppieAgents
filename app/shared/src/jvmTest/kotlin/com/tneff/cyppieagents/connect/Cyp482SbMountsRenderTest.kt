package com.tneff.cyppieagents.connect

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteSessionState
import kotlin.test.Test

/**
 * CYP-482 S-B §9 — the 3 screen mounts wire into RemoteConnectingView at their states, render-testable now; the
 * real state-sources stay seams (INERT until the RR5 runway). Each mount is an OPTIONAL seam param: injected ⇒
 * the merged screen renders at that state; null (the current callers) ⇒ byte-identical to today (INERT).
 *  - §3 OOB-confirm @ TRUST_CHECK (real fingerprint) — the FirstUse mandatory gate;
 *  - §4 PoP prompt inline @ AUTHENTICATING (slot);
 *  - §6 revoke control @ CONNECTED.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp482SbMountsRenderTest {

    private val hub = HubDescriptor("hub-a", "host-a", online = true, defaultPort = 8787, lastSeen = 1L)
    private fun vm() = HubConnectViewModel(StubControlPlaneClient(), StubHubCredentialRepository(), StubLocalConnectFeed())
    private fun rs(conn: RemoteConnState) = RemoteSessionState("hub-a", conn)
    private val dhPubKey = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun trustCheck_withOobMount_rendersOobConfirmScreen_notProvisional() = runComposeUiTest {
        val mount = OobConfirmMount(dhPubKey, onConfirm = {}, onReject = {})
        setContent { MaterialTheme { RemoteConnectingView(hub, rs(RemoteConnState.TRUST_CHECK), vm(), oobConfirm = mount) } }
        onNodeWithTag(RemoteConnectTags.TRUST_FIRST, useUnmergedTree = true).assertExists() // the OOB-confirm screen
        onNodeWithTag(RemoteConnectTags.TRUST_CONFIRM, useUnmergedTree = true).assertExists()
        // the OOB screen REPLACES the provisional spinner (tag TRUST_CHECK); it carries its OWN provisional
        // disclosure inside (§5/Q3), so TRUST_PROVISIONAL is still present — the spinner is what's gone.
        onNodeWithTag(RemoteConnectTags.TRUST_CHECK, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun trustCheck_withoutMount_isInert_showsProvisional() = runComposeUiTest {
        setContent { MaterialTheme { RemoteConnectingView(hub, rs(RemoteConnState.TRUST_CHECK), vm()) } }
        onNodeWithTag(RemoteConnectTags.TRUST_PROVISIONAL, useUnmergedTree = true).assertExists() // byte-identical to today
        onNodeWithTag(RemoteConnectTags.TRUST_FIRST, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun authenticating_withPopMount_rendersSlot() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RemoteConnectingView(hub, rs(RemoteConnState.AUTHENTICATING), vm(), popPrompt = { Text("pop", Modifier.testTag("popSlot")) })
            }
        }
        onNodeWithTag("popSlot", useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteConnectTags.AUTHENTICATING, useUnmergedTree = true).assertDoesNotExist() // slot replaced the spinner
    }

    @Test
    fun connected_withEndSession_rendersRevokeControl() = runComposeUiTest {
        setContent { MaterialTheme { RemoteConnectingView(hub, rs(RemoteConnState.CONNECTED), vm(), onEndSession = {}) } }
        onNodeWithTag(RemoteConnectTags.CONNECTED, useUnmergedTree = true).assertExists() // ● LIVE stays
        onNodeWithTag(RemoteRevokeTags.END, useUnmergedTree = true).assertExists() // + revoke control
    }

    @Test
    fun connected_withoutEndSession_isInert_noRevoke() = runComposeUiTest {
        setContent { MaterialTheme { RemoteConnectingView(hub, rs(RemoteConnState.CONNECTED), vm()) } }
        onNodeWithTag(RemoteConnectTags.CONNECTED, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteRevokeTags.END, useUnmergedTree = true).assertDoesNotExist() // byte-identical to today
    }
}
