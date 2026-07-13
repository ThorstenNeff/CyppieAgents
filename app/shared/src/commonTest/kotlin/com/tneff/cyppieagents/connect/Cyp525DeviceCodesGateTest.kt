package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteSessionState
import com.tneff.cyppieagents.operator.BACKUP_CODE_COUNT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-525 GE2 — the load-bearing lockout gate ([deviceCodesGate]), pure: at CONNECTED with un-acknowledged
 * first-enroll codes the flow HOLDS at the reveal (never CONNECTED); once acknowledged (within-flow) it proceeds; a
 * returning device (no codes, hub `firstEnroll==false`) connects directly; the gate acts ONLY at CONNECTED.
 */
class Cyp525DeviceCodesGateTest {

    private val hub = HubDescriptor("hub-a", "Mein Hub", online = true, defaultPort = 8787, lastSeen = 1L)
    private fun rs(conn: RemoteConnState) = RemoteSessionState("hub-a", conn)
    private val codes = listOf("AAA-1", "BBB-2")

    @Test
    fun firstEnroll_notAcked_holdsAtReveal_neverConnected() {
        val s = deviceCodesGate(hub, rs(RemoteConnState.CONNECTED), codes, codesAcked = false)
        assertTrue(s is HubConnectUiState.RevealCodes, "first-enroll + un-acked ⇒ HOLD at the reveal, NOT CONNECTED")
        assertEquals(codes, (s as HubConnectUiState.RevealCodes).codes)
    }

    @Test
    fun acked_proceedsToConnected() {
        val s = deviceCodesGate(hub, rs(RemoteConnState.CONNECTED), codes, codesAcked = true)
        assertTrue(s is HubConnectUiState.RemoteConnecting)
        assertEquals(RemoteConnState.CONNECTED, (s as HubConnectUiState.RemoteConnecting).remote.conn)
    }

    @Test
    fun returningDevice_noFirstEnrollCodes_connectsDirectly() {
        val s = deviceCodesGate(hub, rs(RemoteConnState.CONNECTED), codes = null, codesAcked = false)
        assertTrue(s is HubConnectUiState.RemoteConnecting)
        assertEquals(RemoteConnState.CONNECTED, (s as HubConnectUiState.RemoteConnecting).remote.conn)
    }

    @Test
    fun gateActsOnlyAtConnected_neverBefore() {
        val s = deviceCodesGate(hub, rs(RemoteConnState.AUTHENTICATING), codes, codesAcked = false)
        assertTrue(s is HubConnectUiState.RemoteConnecting, "the gate holds ONLY at CONNECTED, never before")
    }

    // --- H3 (Reviewer): the ack gates on a validated, complete, non-empty code set — never a bare button-press ---

    @Test
    fun h3_validCodeSet_isExactlyExpectedCount_nonBlank() {
        assertTrue(isValidCodeSet(List(BACKUP_CODE_COUNT) { "code-$it" }), "exactly N non-blank codes = valid")
    }

    @Test
    fun h3_empty_truncated_overCount_orBlank_areInvalid() {
        assertTrue(!isValidCodeSet(emptyList()), "empty ⇒ invalid (never ack codes the user never had)")
        assertTrue(!isValidCodeSet(List(BACKUP_CODE_COUNT - 1) { "c$it" }), "truncated ⇒ invalid")
        assertTrue(!isValidCodeSet(List(BACKUP_CODE_COUNT + 1) { "c$it" }), "over-count ⇒ invalid")
        val withBlank = List(BACKUP_CODE_COUNT) { if (it == 0) "  " else "c$it" }
        assertTrue(!isValidCodeSet(withBlank), "a blank entry ⇒ invalid")
    }
}
