package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.model.AgentBusyStateEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-324 — the busy/idle holder (twin of [AgentTokenUsageTracker]). Latest-wins (not an append log): a new
 * state replaces the old; the snapshot is one-per-agent so a reconnect can't duplicate or lose. De-dups
 * unchanged states so a run of same-state signals doesn't spam the socket. Reset → idle; forget → gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentBusyStateTrackerTest {

    @Test fun snapshot_isLatestWins_onePerAgent() {
        val t = AgentBusyStateTracker()
        t.set("backend", true)
        t.set("backend", false) // replaces, not appends
        t.set("frontend", true)
        assertEquals(
            setOf(AgentBusyStateEvent("backend", false), AgentBusyStateEvent("frontend", true)),
            t.snapshot().toSet(),
        )
    }

    @Test fun forget_dropsTheAgentFromSnapshot() {
        val t = AgentBusyStateTracker()
        t.set("backend", true)
        t.forget("backend")
        assertEquals(emptyList(), t.snapshot())
    }

    @Test fun reset_forcesIdle() {
        val t = AgentBusyStateTracker()
        t.set("backend", true)
        t.reset("backend")
        assertEquals(listOf(AgentBusyStateEvent("backend", false)), t.snapshot())
    }

    @Test fun emitsOnlyOnChange_dedupsUnchanged() = runTest {
        val t = AgentBusyStateTracker()
        val deltas = ArrayList<AgentBusyStateEvent>()
        val job = launch { t.events.collect { deltas.add(it) } }
        runCurrent() // let the collector subscribe BEFORE any emission (SharedFlow has no replay)

        t.set("backend", false) // prev == null → a change → emit (a turn-end for a never-busy agent still shows idle)
        t.set("backend", true)
        t.set("backend", true)  // unchanged → suppressed
        t.set("backend", false)
        t.set("backend", false) // unchanged → suppressed
        runCurrent()            // drain the emissions to the collector
        job.cancel()

        assertEquals(
            listOf(
                AgentBusyStateEvent("backend", false),
                AgentBusyStateEvent("backend", true),
                AgentBusyStateEvent("backend", false),
            ),
            deltas,
        )
    }
}
