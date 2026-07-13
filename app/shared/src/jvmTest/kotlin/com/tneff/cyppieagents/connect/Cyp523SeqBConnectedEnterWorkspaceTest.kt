package com.tneff.cyppieagents.connect

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteSessionState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-523 — Seq-B CONNECTED is no longer a dead-end. Teeth: the „Loslegen" enter-workspace button is present AND
 * invokes `onEnterWorkspace` at the local `Connected` + remote `CONNECTED` states; the remote end-session control
 * mounts when `onEndSession` is wired (→ `backToHubList` at the live callsite). GUARD (the honesty rail): no earlier
 * connect state (attempting / dialing / trust-check+OOB) leaks an enter-workspace affordance, and the mandatory OOB
 * confirm stays blocking (confirm/reject only, no „Loslegen" escape) — so the fix removes the dead-end WITHOUT
 * weakening the First-Use trust gate.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp523SeqBConnectedEnterWorkspaceTest {

    private val hub = HubDescriptor("hub-a", "host-a", online = true, defaultPort = 8787, lastSeen = 1L)
    private fun vm() = HubConnectViewModel(StubControlPlaneClient(), StubHubCredentialRepository(), StubLocalConnectFeed())
    private fun rs(conn: RemoteConnState) = RemoteSessionState("hub-a", conn)

    // --- local (§6) ---
    @Test
    fun localConnected_showsEnterWorkspace_andInvokes() = runComposeUiTest {
        var entered = false
        setContent { MaterialTheme { ConnectingView(hub, ConnectProgress.Connected, vm(), onEnterWorkspace = { entered = true }) } }
        onNodeWithTag(HubConnectTags.STATE_TO_WORKSPACE).assertExists().performClick()
        assertTrue(entered, "enter-workspace at local Connected must invoke onEnterWorkspace")
    }

    @Test
    fun localAttempting_noEnterWorkspace_guard() = runComposeUiTest {
        setContent { MaterialTheme { ConnectingView(hub, ConnectProgress.Attempting, vm(), onEnterWorkspace = {}) } }
        onNodeWithTag(HubConnectTags.STATE_ATTEMPTING, useUnmergedTree = true).assertExists()
        onNodeWithTag(HubConnectTags.STATE_TO_WORKSPACE, useUnmergedTree = true).assertDoesNotExist()
    }

    // --- remote (§7) ---
    @Test
    fun remoteConnected_showsEnterWorkspace_andInvokes() = runComposeUiTest {
        var entered = false
        setContent { MaterialTheme { RemoteConnectingView(hub, rs(RemoteConnState.CONNECTED), vm(), onEnterWorkspace = { entered = true }) } }
        onNodeWithTag(RemoteConnectTags.TO_WORKSPACE).assertExists().performClick()
        assertTrue(entered, "enter-workspace at remote CONNECTED must invoke onEnterWorkspace")
    }

    @Test
    fun remoteConnected_mountsEndSessionControl_whenWired() = runComposeUiTest {
        // B: passing onEndSession = backToHubList (at the live callsite) mounts the already-built revoke/switch control.
        setContent { MaterialTheme { RemoteConnectingView(hub, rs(RemoteConnState.CONNECTED), vm(), onEndSession = {}) } }
        onNodeWithTag(RemoteRevokeTags.END, useUnmergedTree = true).assertExists()
    }

    @Test
    fun remoteDialing_noEnterWorkspace_guard() = runComposeUiTest {
        setContent { MaterialTheme { RemoteConnectingView(hub, rs(RemoteConnState.RELAY_DIALING), vm(), onEnterWorkspace = {}) } }
        onNodeWithTag(RemoteConnectTags.RELAY_DIALING, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteConnectTags.TO_WORKSPACE, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun oobConfirm_staysBlocking_noEnterWorkspace_guard() = runComposeUiTest {
        // The mandatory First-Use OOB gate must not gain an escape hatch: confirm + reject present, NO „Loslegen".
        val mount = OobConfirmMount(ByteArray(32) { it.toByte() }, provisional = false, onConfirm = {}, onReject = {})
        setContent { MaterialTheme { RemoteConnectingView(hub, rs(RemoteConnState.TRUST_CHECK), vm(), oobConfirm = mount, onEnterWorkspace = {}) } }
        onNodeWithTag(RemoteConnectTags.TRUST_CONFIRM, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteConnectTags.TRUST_REJECT, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteConnectTags.TO_WORKSPACE, useUnmergedTree = true).assertDoesNotExist()
    }
}
