package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.model.AgentTokenUsageEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-316 — the per-agent token feed holder. Latest-wins (not an append log): a new value replaces the
 * old; the snapshot is one-per-agent so a reconnect can't duplicate or lose. De-dups unchanged values
 * (so a Connector-B agent's repeated null doesn't spam the socket). Reset → null; forget → gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentTokenUsageTrackerTest {

    @Test fun snapshot_isLatestWins_onePerAgent() {
        val t = AgentTokenUsageTracker()
        t.onResult("backend", 100)
        t.onResult("backend", 250) // replaces, not appends
        t.onResult("po", null)     // an unknown-value agent is still present, as null
        assertEquals(
            setOf(AgentTokenUsageEvent("backend", 250), AgentTokenUsageEvent("po", null)),
            t.snapshot().toSet(),
        )
    }

    @Test fun forget_dropsTheAgentFromSnapshot() {
        val t = AgentTokenUsageTracker()
        t.onResult("backend", 100)
        t.forget("backend")
        assertEquals(emptyList(), t.snapshot())
    }

    @Test fun reset_setsNull() {
        val t = AgentTokenUsageTracker()
        t.onResult("backend", 100)
        t.reset("backend")
        assertEquals(listOf(AgentTokenUsageEvent("backend", null)), t.snapshot())
    }

    @Test fun emitsOnlyOnChange_dedupsUnchanged() = runTest {
        val t = AgentTokenUsageTracker()
        val deltas = ArrayList<AgentTokenUsageEvent>()
        val job = launch { t.events.collect { deltas.add(it) } }
        runCurrent() // let the collector subscribe BEFORE any emission (SharedFlow has no replay)

        t.onResult("backend", 100)
        t.onResult("backend", 100) // unchanged → suppressed
        t.onResult("backend", 200)
        t.reset("backend")         // → null: a change → emit
        t.reset("backend")         // already null → suppressed
        runCurrent()               // drain the emissions to the collector
        job.cancel()

        assertEquals(
            listOf(
                AgentTokenUsageEvent("backend", 100),
                AgentTokenUsageEvent("backend", 200),
                AgentTokenUsageEvent("backend", null),
            ),
            deltas,
        )
    }
}
