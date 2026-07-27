package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.model.AgentTokenUsageEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-316 — the [TokenUsageViewModel] contract: it upserts by `agentId` (latest-wins, idempotent) into its map,
 * storing `contextTokens == null` AS null (unknown), never coerced to 0.
 *
 * CYP-846: the socket-decode half of this seam (a `TokenUsageStatus` frame off the muxed `/ws/status`, null
 * preserved) is now covered by `Cyp846StatusMuxTest` (the token `LiveSource` was folded into `StatusMuxClient`).
 * The VM is unchanged, and it consumes any [TokenUsageSource] (here a stub), so this test stands untouched.
 */
class TokenUsageClientTest {

    private class FakeSource(private val events: List<AgentTokenUsageEvent>) : TokenUsageSource {
        override fun events(): Flow<AgentTokenUsageEvent> = events.asFlow()
    }

    @Test
    fun viewModel_upsertsByAgentId_latestWins_storesNull() {
        val scope = CoroutineScope(Dispatchers.Unconfined) // init collect settles synchronously
        try {
            val vm = TokenUsageViewModel(
                FakeSource(
                    listOf(
                        AgentTokenUsageEvent("backend", 100),
                        AgentTokenUsageEvent("frontend", null),
                        AgentTokenUsageEvent("backend", 200), // re-delivered/updated → overwrites, never appends
                    ),
                ),
                scope,
            )
            val map = vm.tokens.value
            assertEquals(200, map["backend"], "latest-wins: the newer value replaces the older (idempotent upsert)")
            assertTrue(map.containsKey("frontend"))
            assertNull(map["frontend"], "a null event is stored AS null (unknown) — never 0 (the §8-3 honesty core)")
        } finally {
            scope.cancel()
        }
    }
}
