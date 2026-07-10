package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.model.AgentTerminalControlEvent
import com.tneff.cyppieagents.model.TerminalControlState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-354 (BE-1) — the terminal-control-mode holder (twin of [AgentBusyStateTracker]). Latest-wins (not an
 * append log): a transition replaces the old; the snapshot is one-per-agent so a reconnect can't duplicate or
 * lose. De-dups unchanged values (incl. heldBy/since) so a repeat doesn't spam the socket. Reset → MEDIATED
 * (dropping the holder); forget → gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalControlStateTrackerTest {

    @Test fun snapshot_isLatestWins_onePerAgent_withHolderAndSince() {
        val t = TerminalControlStateTracker()
        t.set("backend", TerminalControlState.HANDING_OVER)
        t.set("backend", TerminalControlState.INTERACTIVE, heldBy = "op-1", since = 123L) // replaces, not appends
        t.set("frontend", TerminalControlState.MEDIATED)
        assertEquals(
            setOf(
                AgentTerminalControlEvent("backend", TerminalControlState.INTERACTIVE, "op-1", 123L),
                AgentTerminalControlEvent("frontend", TerminalControlState.MEDIATED),
            ),
            t.snapshot().toSet(),
        )
    }

    @Test fun reset_forcesMediated_droppingTheHolder() {
        val t = TerminalControlStateTracker()
        t.set("backend", TerminalControlState.INTERACTIVE, heldBy = "op-1", since = 123L)
        t.reset("backend") // a stop/restart ends the hand-off → back to MEDIATED, holder cleared
        assertEquals(listOf(AgentTerminalControlEvent("backend", TerminalControlState.MEDIATED)), t.snapshot())
    }

    @Test fun forget_dropsTheAgentFromSnapshot() {
        val t = TerminalControlStateTracker()
        t.set("backend", TerminalControlState.INTERACTIVE)
        t.forget("backend")
        assertEquals(emptyList(), t.snapshot())
    }

    @Test fun emitsOnlyOnChange_dedupsUnchanged_inclHolder() = runTest {
        val t = TerminalControlStateTracker()
        val deltas = ArrayList<AgentTerminalControlEvent>()
        val job = launch { t.events.collect { deltas.add(it) } }
        runCurrent() // let the collector subscribe BEFORE any emission (SharedFlow has no replay)

        t.set("backend", TerminalControlState.MEDIATED)                               // prev null → change → emit
        t.set("backend", TerminalControlState.INTERACTIVE, heldBy = "op-1", since = 5L)
        t.set("backend", TerminalControlState.INTERACTIVE, heldBy = "op-1", since = 5L) // unchanged → suppressed
        t.set("backend", TerminalControlState.INTERACTIVE, heldBy = "op-2", since = 9L) // SAME state, new holder → emit
        t.reset("backend")                                                            // → MEDIATED → emit
        runCurrent()
        job.cancel()

        assertEquals(
            listOf(
                AgentTerminalControlEvent("backend", TerminalControlState.MEDIATED),
                AgentTerminalControlEvent("backend", TerminalControlState.INTERACTIVE, "op-1", 5L),
                AgentTerminalControlEvent("backend", TerminalControlState.INTERACTIVE, "op-2", 9L),
                AgentTerminalControlEvent("backend", TerminalControlState.MEDIATED),
            ),
            deltas,
        )
    }
}
