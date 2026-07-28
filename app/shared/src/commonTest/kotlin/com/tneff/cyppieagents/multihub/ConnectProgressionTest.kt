package com.tneff.cyppieagents.multihub

import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-866 — [progressionStateFor]: the six transient connect phases map to an in-flight [ProgressionState]; the
 * terminal [RemoteConnState.LOST] maps to **null** (not an in-flight progression — the Failure-Region CYP-823 owns
 * it). The exhaustive `when` (no `else`) is the compile-time assertNever twin (a new phase breaks compile).
 */
class ConnectProgressionTest {

    @Test
    fun sixTransientPhases_mapToTheirProgressionStep() {
        assertEquals(ProgressionState.DIALING, progressionStateFor(RemoteConnState.RELAY_DIALING))
        assertEquals(ProgressionState.HANDSHAKE, progressionStateFor(RemoteConnState.E2E_HANDSHAKE))
        assertEquals(ProgressionState.TRUST_CHECK, progressionStateFor(RemoteConnState.TRUST_CHECK))
        assertEquals(ProgressionState.AUTHENTICATING, progressionStateFor(RemoteConnState.AUTHENTICATING))
        assertEquals(ProgressionState.RECONNECTING, progressionStateFor(RemoteConnState.RECONNECTING))
        assertEquals(ProgressionState.CONNECTED, progressionStateFor(RemoteConnState.CONNECTED))
    }

    @Test
    fun lost_isTerminal_mapsToNull_notAProgressionStep() {
        // The load-bearing terminal→null: LOST is ended → the Failure-Region, NEVER an in-flight progression step.
        // Mutation: LOST → any ProgressionState → RED.
        assertNull(progressionStateFor(RemoteConnState.LOST))
    }

    @Test
    fun reconnecting_staysInFlight_notNull() {
        // Honesty: reconnecting is honestly-uncertain + retryable — an in-flight progression, NEVER routed to terminal.
        // Mutation: RECONNECTING → null → RED.
        assertEquals(ProgressionState.RECONNECTING, progressionStateFor(RemoteConnState.RECONNECTING))
    }

    @Test
    fun exactly_lost_isNull_everyOtherPhase_isAProgressionStep() {
        // Covers the whole enum: exactly LOST → null, every other phase → a non-null progression step. A wrong
        // terminal-null classification (or a new phase left unrouted) is caught here (+ the compile-time exhaustive when).
        for (conn in RemoteConnState.entries) {
            val mapped = progressionStateFor(conn)
            if (conn == RemoteConnState.LOST) {
                assertNull(mapped, "LOST is terminal → null")
            } else {
                assertEquals(false, mapped == null, "$conn is an in-flight progression step → non-null")
            }
        }
    }
}
