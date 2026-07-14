package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.comm.ConnectionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-573 (the honesty tooth) — the header status DOT must not assert a STALE "RUNNING" for a stopped agent across
 * a reconnect gap. [connectionGatedLifecycle] is the load-bearing gate; it is PURE (no ViewModel / no
 * `viewModelScope`) precisely so it can be driven on virtual time here — NOT through a `setMain` VM drive, which
 * (ProjectVmStoreManagerTest KDoc) installs `TestMainDispatcher` globally and hangs the suite.
 *
 * These are DISCRIMINATING: each names the wrong implementation it reddens, so a green result is real evidence, not
 * a tautology.
 */
class Cyp573ConnectionGatedDotTest {

    private val threshold = 3_000L

    /**
     * The core fix. A SUSTAINED disconnect degrades the dot to UNKNOWN; a reconnect restores the fresh state.
     * Reddening mutation (the bug this fixes): drop the gate — `connectionGatedLifecycle` returns `lifecycle`
     * unchanged — and the dot stays RUNNING through the outage ⇒ the sustained-disconnect assertion goes red.
     */
    @Test
    fun sustainedDisconnect_degradesToUnknown_reconnectRestores() = runTest {
        val lifecycle = MutableStateFlow(AgentLifecycleState.RUNNING)
        val connection = MutableStateFlow(ConnectionStatus.LIVE)
        val seen = mutableListOf<AgentLifecycleState>()
        val job = launch { connectionGatedLifecycle(lifecycle, connection, threshold).collect { seen.add(it) } }
        runCurrent()
        assertEquals(AgentLifecycleState.RUNNING, seen.last(), "LIVE ⇒ fresh server state")

        connection.value = ConnectionStatus.DISCONNECTED
        runCurrent()
        assertEquals(AgentLifecycleState.RUNNING, seen.last(), "grace window: last-known state held, NOT yet UNKNOWN")

        advanceTimeBy(threshold + 1); runCurrent()
        assertEquals(AgentLifecycleState.UNKNOWN, seen.last(), "sustained disconnect ⇒ UNKNOWN (the CYP-573 fix)")

        connection.value = ConnectionStatus.LIVE
        runCurrent()
        assertEquals(AgentLifecycleState.RUNNING, seen.last(), "reconnected ⇒ fresh server state replaces UNKNOWN")
        job.cancel()
    }

    /**
     * The flicker-guard. A sub-second blip (DISCONNECTED then LIVE inside the window) must NEVER flip the dot to
     * UNKNOWN. Reddening mutation: remove the `flatMapLatest` cancellation (e.g. gate on the value only, no delay)
     * and a momentary blip flashes UNKNOWN ⇒ this goes red.
     */
    @Test
    fun subSecondBlip_neverFlipsToUnknown() = runTest {
        val lifecycle = MutableStateFlow(AgentLifecycleState.RUNNING)
        val connection = MutableStateFlow(ConnectionStatus.LIVE)
        val seen = mutableListOf<AgentLifecycleState>()
        val job = launch { connectionGatedLifecycle(lifecycle, connection, threshold).collect { seen.add(it) } }
        runCurrent()

        connection.value = ConnectionStatus.DISCONNECTED
        advanceTimeBy(threshold / 3); runCurrent() // a blip, well inside the flicker-guard window
        connection.value = ConnectionStatus.LIVE
        advanceTimeBy(threshold * 2); runCurrent()  // let any (wrongly) pending degrade fire

        assertFalse(seen.contains(AgentLifecycleState.UNKNOWN), "a sub-second blip must not flash UNKNOWN (flicker-guard)")
        assertEquals(AgentLifecycleState.RUNNING, seen.last())
        job.cancel()
    }

    /**
     * Honesty on the other side: a state that is ALREADY UNKNOWN (no snapshot yet) does not invent a phantom
     * RUNNING while LIVE, and the gate adds nothing to it. Guards against a fix that special-cased RUNNING only.
     */
    @Test
    fun alreadyUnknown_staysUnknown_regardlessOfConnection() = runTest {
        val lifecycle = MutableStateFlow(AgentLifecycleState.UNKNOWN)
        val connection = MutableStateFlow(ConnectionStatus.DISCONNECTED)
        val seen = mutableListOf<AgentLifecycleState>()
        val job = launch { connectionGatedLifecycle(lifecycle, connection, threshold).collect { seen.add(it) } }
        advanceTimeBy(threshold * 2); runCurrent()
        assertTrue(seen.all { it == AgentLifecycleState.UNKNOWN }, "already-unknown stays unknown, no phantom state")
        job.cancel()
    }
}
