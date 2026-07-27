package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.model.AgentTerminalControlEvent
import com.tneff.cyppieagents.model.TerminalControlState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * CYP-354 (client mirror) — the [TerminalControlStateViewModel] contract: it upserts by `agentId` (latest-wins,
 * idempotent) — a reconnect snapshot while INTERACTIVE re-delivers INTERACTIVE and is applied over the old value,
 * never duplicated; an unseen agent is absent (never a fabricated MEDIATED entry).
 *
 * CYP-846: the socket-decode half of this seam (a `TerminalStatus` frame off the muxed `/ws/status`, holder +
 * since carried) is now covered by `Cyp846StatusMuxTest` (the terminal-control `LiveSource` was folded into
 * `StatusMuxClient`). The VM is unchanged, and it consumes any [TerminalControlSource] (here a stub), so this test
 * stands untouched.
 */
class TerminalControlStateClientTest {

    private class FakeSource(private val events: List<AgentTerminalControlEvent>) : TerminalControlSource {
        override fun events(): Flow<AgentTerminalControlEvent> = events.asFlow()
    }

    @Test
    fun viewModel_upsertsByAgentId_latestWins_absentStaysAbsent() {
        val scope = CoroutineScope(Dispatchers.Unconfined) // init collect settles synchronously
        try {
            val vm = TerminalControlStateViewModel(
                FakeSource(
                    listOf(
                        AgentTerminalControlEvent("backend", TerminalControlState.INTERACTIVE, heldBy = "op-1", since = 1000L),
                        AgentTerminalControlEvent("frontend", TerminalControlState.HANDING_OVER),
                        AgentTerminalControlEvent("backend", TerminalControlState.MEDIATED), // delta → overwrites backend, never appends
                        AgentTerminalControlEvent("db", TerminalControlState.INTERACTIVE, heldBy = "op-2", since = 2000L), // only ever interactive → stays
                    ),
                ),
                scope,
            )
            val map = vm.states.value
            // latest-wins: the newer MEDIATED replaces the older INTERACTIVE (idempotent upsert, not append).
            assertEquals(TerminalControlState.MEDIATED, map["backend"]?.state)
            assertEquals(null, map["backend"]?.heldBy, "handing back clears the holder")
            assertEquals(TerminalControlState.HANDING_OVER, map["frontend"]?.state)
            assertEquals(TerminalControlState.INTERACTIVE, map["db"]?.state, "an agent only ever interactive stays interactive")
            assertEquals("op-2", map["db"]?.heldBy)
            // Honesty (absent == MEDIATED): an unseen agent is simply absent — the lookup, not the map, defaults it.
            assertFalse(map.containsKey("unseen"), "an agent with no event is absent, never a fabricated MEDIATED entry")
        } finally {
            scope.cancel()
        }
    }
}
