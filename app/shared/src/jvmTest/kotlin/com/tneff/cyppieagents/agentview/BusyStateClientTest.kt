package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.model.AgentBusyStateEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-324 — the [BusyStateViewModel] contract: it upserts by `agentId` (latest-wins, idempotent) into its map —
 * a reconnect snapshot mid-turn re-delivers `busy = true` and is applied over the old value, never duplicated.
 *
 * CYP-846: the socket-decode half of this seam (a `BusyStatus` frame off the muxed `/ws/status`) is now covered
 * by `Cyp846StatusMuxTest` (the busy `LiveSource` was folded into `StatusMuxClient`). The VM is unchanged, and it
 * consumes any [BusyStateSource] (here a stub), so this test stands untouched.
 */
class BusyStateClientTest {

    private class FakeSource(private val events: List<AgentBusyStateEvent>) : BusyStateSource {
        override fun events(): Flow<AgentBusyStateEvent> = events.asFlow()
    }

    @Test
    fun viewModel_upsertsByAgentId_latestWins() {
        val scope = CoroutineScope(Dispatchers.Unconfined) // init collect settles synchronously
        try {
            val vm = BusyStateViewModel(
                FakeSource(
                    listOf(
                        AgentBusyStateEvent("backend", true),
                        AgentBusyStateEvent("frontend", false),
                        AgentBusyStateEvent("backend", false), // delta → overwrites backend, never appends
                        AgentBusyStateEvent("db", true),        // only ever busy → stays busy (reconnect-mid-turn kin)
                    ),
                ),
                scope,
            )
            val map = vm.busy.value
            assertFalse(map["backend"] ?: true, "latest-wins: the newer false replaces the older true (idempotent upsert)")
            assertFalse(map["frontend"] ?: true, "explicit false is stored as false")
            assertTrue(map["db"] ?: false, "an agent that is only ever busy stays busy")
        } finally {
            scope.cancel()
        }
    }
}
