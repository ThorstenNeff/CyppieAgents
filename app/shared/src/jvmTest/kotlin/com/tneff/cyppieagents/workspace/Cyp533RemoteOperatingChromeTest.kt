package com.tneff.cyppieagents.workspace

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.connect.RemoteRevokeTags
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-427/M2 (Seams #4/#6/#8) — the remote-operating-chrome region state machine + the frozen present-iff guards
 * (G1–G6) and honesty invariants. Drives [RemoteOperatingChrome] directly (hermetic render test) across B1–B5.
 *
 * Copy is asserted in EN (jvmTest resolves the en locale) to prove the _partial↔_tunnel graduation; the tone is
 * asserted via the GLYPH (`▲` WARN vs `●` neutral, locale-independent) — the load-bearing "neutral ≠ green,
 * drop ≠ red" honesty signal (WCAG 1.4.1: colour is never the sole carrier, so the glyph IS the testable signal).
 */
@OptIn(ExperimentalTestApi::class)
class Cyp533RemoteOperatingChromeTest {

    private fun flow(conn: RemoteConnState, inFlightUncertain: Boolean = false) =
        MutableStateFlow(RemoteSessionState(hubId = "h1", conn = conn, inFlightUncertain = inFlightUncertain))

    // --- B1: local / no session → the whole region is absent (fail-closed) ---
    @Test
    fun b1_local_absent() = runComposeUiTest {
        setContent { MaterialTheme { RemoteOperatingChrome(hubName = null, sessionState = null, onEndSession = {}) } }
        onNodeWithTag(WorkspaceTags.REMOTE_CONTEXT, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(WorkspaceTags.RELAY_DROP, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(RemoteRevokeTags.END, useUnmergedTree = true).assertDoesNotExist()
    }

    // --- B2: CONNECTED, data NOT over the tunnel → WARN-partial banner (▲) + trailing revoke; no PINNED (G1) ---
    @Test
    fun b2_connected_partial_warn_withRevoke_noPinned() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RemoteOperatingChrome(
                    hubName = "Mein Hub", sessionState = flow(RemoteConnState.CONNECTED),
                    onEndSession = {}, dataOverTunnel = false, pinned = true, // pinned=true must NOT show in B2
                )
            }
        }
        onNodeWithTag(WorkspaceTags.REMOTE_CONTEXT, useUnmergedTree = true).assertExists()
        onNodeWithText("▲", useUnmergedTree = true).assertExists() // WARN tone
        onNodeWithText("●", useUnmergedTree = true).assertDoesNotExist() // NOT the neutral affirmative form
        onNodeWithText("over the tunnel yet", substring = true, useUnmergedTree = true).assertExists() // _partial copy
        onNodeWithTag(RemoteRevokeTags.END, useUnmergedTree = true).assertExists() // G6 revoke present
        onNodeWithTag(WorkspaceTags.REMOTE_CONTEXT_PINNED, useUnmergedTree = true).assertDoesNotExist() // pinned only in B3
    }

    // --- B3: CONNECTED, data over the tunnel → affirmative NEUTRAL banner (● + "encrypted tunnel"), never WARN/green ---
    @Test
    fun b3_connected_tunnel_neutral_withRevoke() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RemoteOperatingChrome(
                    hubName = "Mein Hub", sessionState = flow(RemoteConnState.CONNECTED),
                    onEndSession = {}, dataOverTunnel = true, pinned = false,
                )
            }
        }
        onNodeWithTag(WorkspaceTags.REMOTE_CONTEXT, useUnmergedTree = true).assertExists()
        onNodeWithText("●", useUnmergedTree = true).assertExists() // NEUTRAL tone
        onNodeWithText("▲", useUnmergedTree = true).assertDoesNotExist() // no longer a WARN
        onNodeWithText("over an encrypted tunnel", substring = true, useUnmergedTree = true).assertExists() // _tunnel copy
        onNodeWithTag(RemoteRevokeTags.END, useUnmergedTree = true).assertExists()
        // G2/HC: pinned=false ⇒ the pinned sub-node is ABSENT (never a faked pin)
        onNodeWithTag(WorkspaceTags.REMOTE_CONTEXT_PINNED, useUnmergedTree = true).assertDoesNotExist()
    }

    // --- B3 + real pin → the .pinned sub-node is present (G2) ---
    @Test
    fun b3_pinned_present_iff_realPin() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RemoteOperatingChrome(
                    hubName = "Mein Hub", sessionState = flow(RemoteConnState.CONNECTED),
                    onEndSession = {}, dataOverTunnel = true, pinned = true,
                )
            }
        }
        onNodeWithTag(WorkspaceTags.REMOTE_CONTEXT_PINNED, useUnmergedTree = true).assertExists()
    }

    // --- B4: RECONNECTING → relay-drop banner (WARN ▲); context banner + revoke ABSENT (G5 exclusivity, G6) ---
    @Test
    fun b4_reconnecting_relayDrop_noContext_noRevoke() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RemoteOperatingChrome(
                    hubName = "Mein Hub", sessionState = flow(RemoteConnState.RECONNECTING),
                    onEndSession = {}, dataOverTunnel = true,
                )
            }
        }
        onNodeWithTag(WorkspaceTags.RELAY_DROP, useUnmergedTree = true).assertExists()
        onNodeWithText("▲", useUnmergedTree = true).assertExists() // WARN tone (never red)
        onNodeWithText("●", useUnmergedTree = true).assertDoesNotExist() // never the affirmative neutral form
        onNodeWithTag(WorkspaceTags.REMOTE_CONTEXT, useUnmergedTree = true).assertDoesNotExist() // G5: not both
        onNodeWithTag(RemoteRevokeTags.END, useUnmergedTree = true).assertDoesNotExist() // G6: no revoke while reconnecting
        onNodeWithTag(WorkspaceTags.RELAY_DROP_UNCERTAIN, useUnmergedTree = true).assertDoesNotExist() // not uncertain here
    }

    // --- B4 + inFlightUncertain → the honest "actions unconfirmed" sub-node (G4, H4) ---
    @Test
    fun b4_inFlightUncertain_present() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RemoteOperatingChrome(
                    hubName = "Mein Hub", sessionState = flow(RemoteConnState.RECONNECTING, inFlightUncertain = true),
                    onEndSession = {},
                )
            }
        }
        onNodeWithTag(WorkspaceTags.RELAY_DROP, useUnmergedTree = true).assertExists()
        onNodeWithTag(WorkspaceTags.RELAY_DROP_UNCERTAIN, useUnmergedTree = true).assertExists()
        onNodeWithText("unconfirmed", substring = true, useUnmergedTree = true).assertExists() // never silently "done"
    }

    // --- B5: LOST (terminal) → the region is absent here (session-ended path is out-of-scope) ---
    @Test
    fun b5_lost_absent() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RemoteOperatingChrome(hubName = "Mein Hub", sessionState = flow(RemoteConnState.LOST), onEndSession = {})
            }
        }
        onNodeWithTag(WorkspaceTags.REMOTE_CONTEXT, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(WorkspaceTags.RELAY_DROP, useUnmergedTree = true).assertDoesNotExist()
    }

    // --- CYP-527 fallback: no live flow but a hub name → B2 banner, no revoke ---
    @Test
    fun fallback_hubNameOnly_showsB2_noRevoke() = runComposeUiTest {
        setContent { MaterialTheme { RemoteOperatingChrome(hubName = "Mein Hub", sessionState = null, onEndSession = {}) } }
        onNodeWithTag(WorkspaceTags.REMOTE_CONTEXT, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteRevokeTags.END, useUnmergedTree = true).assertDoesNotExist()
    }

    // --- Seam #8: confirming the revoke invokes the guaranteed local teardown ---
    @Test
    fun revoke_confirm_invokes_onEndSession() = runComposeUiTest {
        var ended = false
        setContent {
            MaterialTheme {
                RemoteOperatingChrome(
                    hubName = "Mein Hub", sessionState = flow(RemoteConnState.CONNECTED), onEndSession = { ended = true },
                )
            }
        }
        onNodeWithTag(RemoteRevokeTags.END, useUnmergedTree = true).performClick()
        onNodeWithTag(RemoteRevokeTags.CONFIRM, useUnmergedTree = true).performClick()
        assertTrue(ended, "confirm → onEndSession (guaranteed local teardown)")
    }
}
