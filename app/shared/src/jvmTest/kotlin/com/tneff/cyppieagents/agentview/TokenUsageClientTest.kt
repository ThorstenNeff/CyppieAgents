package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AgentTokenUsageEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-316 — the client token-usage seam on the frozen contract.
 *  - [TokenUsageLiveSource] decodes the REAL `:core` [AgentTokenUsageEvent] off `/ws/token-usage` (snapshot then
 *    deltas), preserving `contextTokens == null` (never coerced to 0) — no hand-parse.
 *  - [TokenUsageViewModel] upserts by `agentId` (latest-wins, idempotent) into its map.
 */
class TokenUsageClientTest {

    @Test
    fun liveSource_decodesRealEvents_snapshotThenDelta_preservesNull() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing {
                // The server shape: snapshot (one per agent) then a live delta, all as AgentTokenUsageEvent frames.
                webSocket("/ws/token-usage") {
                    send(Frame.Text(CommJson.encodeToString(AgentTokenUsageEvent.serializer(), AgentTokenUsageEvent("backend", 137_000))))
                    send(Frame.Text(CommJson.encodeToString(AgentTokenUsageEvent.serializer(), AgentTokenUsageEvent("frontend", null))))
                    send(Frame.Text(CommJson.encodeToString(AgentTokenUsageEvent.serializer(), AgentTokenUsageEvent("backend", 141_000))))
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                val source = TokenUsageLiveSource(client, "ws://127.0.0.1:$port", token = "op")
                val events = withTimeout(10_000) { source.events().take2(3) }
                assertEquals(AgentTokenUsageEvent("backend", 137_000), events[0])
                assertEquals(AgentTokenUsageEvent("frontend", null), events[1], "null contextTokens must survive the wire — never coerced to 0")
                assertEquals(AgentTokenUsageEvent("backend", 141_000), events[2])
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }

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

/** Collect exactly [n] items from a (reconnecting/endless) flow, then cancel. */
private suspend fun <T> Flow<T>.take2(n: Int): List<T> {
    val out = ArrayList<T>(n)
    try {
        collect {
            out.add(it)
            if (out.size >= n) throw CancellationException("done")
        }
    } catch (_: CancellationException) {
    }
    return out
}
