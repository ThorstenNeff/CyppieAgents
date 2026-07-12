package com.tneff.cyppieagents.connect

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteFailure
import com.tneff.cyppieagents.net.hub.remote.RemoteSessionState
import kotlin.test.Test

/**
 * CYP-471 — the remote-connect render honesty-teeth. `●`-LIVE only on real `CONNECTED`; ONE neutral relay-drop
 * surface; and the H2/fail-closed split: a **terminal** trust-changed/auth-rejected has **no retry**, a retryable
 * transport failure has one. (Colour discipline — neutral/`primary`/error, never tertiary-green — is in the
 * composable via theme roles; a tag test pins the structure UIUX §-QA then checks visually.)
 */
@OptIn(ExperimentalTestApi::class)
class Cyp471RemoteConnectRenderTest {

    private val hub = HubDescriptor("hub-a", "host-a", online = true, defaultPort = 8787, lastSeen = 1L)
    private fun vm() = HubConnectViewModel(StubControlPlaneClient(), StubHubCredentialRepository(), StubLocalConnectFeed())
    private fun rs(conn: RemoteConnState, failure: RemoteFailure? = null) = RemoteSessionState("hub-a", conn, failure)

    @Test
    fun dialing_showsProgress_notConnected() = runComposeUiTest {
        setContent { MaterialTheme { RemoteConnectingView(hub, rs(RemoteConnState.RELAY_DIALING), vm()) } }
        onNodeWithTag(RemoteConnectTags.RELAY_DIALING, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteConnectTags.CONNECTED, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun connected_onlyOnRealConnectedState() = runComposeUiTest {
        setContent { MaterialTheme { RemoteConnectingView(hub, rs(RemoteConnState.CONNECTED), vm()) } }
        onNodeWithTag(RemoteConnectTags.CONNECTED, useUnmergedTree = true).assertExists()
    }

    @Test
    fun reconnecting_isOneNeutralRelayDropSurface() = runComposeUiTest {
        setContent { MaterialTheme { RemoteConnectingView(hub, rs(RemoteConnState.RECONNECTING), vm()) } }
        onNodeWithTag(RemoteConnectTags.RELAY_DROP, useUnmergedTree = true).assertExists()
    }

    @Test
    fun trustCheck_showsProvisionalDisclosure() = runComposeUiTest {
        // CYP-475 §-QA①: the trust-check must NOT over-say "verified" while real pinning is RR5-downstream —
        // the honest "provisional" disclosure is USER-VISIBLE (not just KDoc).
        setContent { MaterialTheme { RemoteConnectingView(hub, rs(RemoteConnState.TRUST_CHECK), vm()) } }
        onNodeWithTag(RemoteConnectTags.TRUST_CHECK, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteConnectTags.TRUST_PROVISIONAL, useUnmergedTree = true).assertExists()
    }

    @Test
    fun trustChanged_isTerminal_noRetry() = runComposeUiTest {
        setContent { MaterialTheme { RemoteConnectingView(hub, rs(RemoteConnState.LOST, RemoteFailure.TrustChanged("ab:cd")), vm()) } }
        onNodeWithTag(RemoteConnectTags.error("trustChanged"), useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteConnectTags.RETRY, useUnmergedTree = true).assertDoesNotExist() // terminal ⇒ no retry
    }

    @Test
    fun handshakeFailed_isRetryable_withRetry() = runComposeUiTest {
        setContent { MaterialTheme { RemoteConnectingView(hub, rs(RemoteConnState.LOST, RemoteFailure.HandshakeFailed), vm()) } }
        onNodeWithTag(RemoteConnectTags.error("handshakeFailed"), useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteConnectTags.RETRY, useUnmergedTree = true).assertExists() // retryable ⇒ retry present
    }
}
