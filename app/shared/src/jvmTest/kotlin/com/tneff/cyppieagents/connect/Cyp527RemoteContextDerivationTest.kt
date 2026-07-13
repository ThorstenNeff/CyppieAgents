package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteSessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-527 — the present-iff GUARDS of the remote-context banner, proven at the pure state→signal derivation
 * (`remoteContextHubName`), so they hold without Compose flakiness (UIUX §-QA guards G1/G3/G4):
 *  - **G1** source = `RemoteSessionState.conn == CONNECTED` on the REMOTE path (not mode-choice, not `entered`);
 *  - **before CONNECTED** (dialing/handshake/trust-check/authenticating/reconnecting) ⇒ no context;
 *  - **G4** cleared at teardown (`LOST`) and `backToHubList` (`HubList`/`LoadingHubs`) ⇒ no stale-true;
 *  - **G3** never on the LOCAL path (`Connecting`, even at `Connected`) ⇒ no false remote claim.
 */
class Cyp527RemoteContextDerivationTest {

    private val hub = HubDescriptor("hub-a", "Mein Hub", online = true, defaultPort = 8787, lastSeen = 1L)
    private fun remote(conn: RemoteConnState) = HubConnectUiState.RemoteConnecting(hub, RemoteSessionState("hub-a", conn))

    @Test
    fun g1_present_onlyAtRemoteConnected_carriesHubName() {
        assertEquals("Mein Hub", remoteContextHubName(remote(RemoteConnState.CONNECTED)))
    }

    @Test
    fun absent_beforeConnected() {
        for (c in listOf(
            RemoteConnState.RELAY_DIALING, RemoteConnState.E2E_HANDSHAKE, RemoteConnState.TRUST_CHECK,
            RemoteConnState.AUTHENTICATING, RemoteConnState.RECONNECTING,
        )) {
            assertNull(remoteContextHubName(remote(c)), "no remote-context before CONNECTED ($c)")
        }
    }

    @Test
    fun g4_cleared_atTeardown_andBackToHubList() {
        assertNull(remoteContextHubName(remote(RemoteConnState.LOST)), "LOST ⇒ cleared (no stale-true)")
        assertNull(remoteContextHubName(HubConnectUiState.HubList(listOf(hub))), "backToHubList ⇒ cleared")
        assertNull(remoteContextHubName(HubConnectUiState.LoadingHubs), "reloading list ⇒ cleared")
    }

    @Test
    fun g3_neverLocal() {
        // The LOCAL connect path is HubConnectUiState.Connecting (never RemoteConnecting) — even at Connected it
        // must NOT surface a remote context (the distinction `entered` cannot make; this derivation can).
        assertNull(remoteContextHubName(HubConnectUiState.Connecting(hub, ConnectProgress.Connected)))
    }
}
