package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AgentTerminalControlEvent
import com.tneff.cyppieagents.model.TerminalControlState
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
import kotlin.test.assertFalse

/**
 * CYP-354 (client mirror) — the read-only terminal-control seam on the frozen BE-1 contract.
 *  - [TerminalControlLiveSource] decodes the REAL `:core` [AgentTerminalControlEvent] off `/ws/terminal-state`
 *    (snapshot then deltas), carrying `state` + holder id + since-time — no hand-parse.
 *  - [TerminalControlStateViewModel] upserts by `agentId` (latest-wins, idempotent) — a reconnect snapshot while
 *    INTERACTIVE re-delivers INTERACTIVE and is applied over the old value, never duplicated.
 * Twin of `BusyStateClientTest` / `TokenUsageClientTest`.
 */
class TerminalControlStateClientTest {

    @Test
    fun liveSource_decodesRealEvents_withHolderAndSince_snapshotThenDelta() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing {
                // Snapshot (one per agent) then a live delta, all as AgentTerminalControlEvent frames.
                webSocket("/ws/terminal-state") {
                    send(Frame.Text(CommJson.encodeToString(AgentTerminalControlEvent.serializer(), AgentTerminalControlEvent("backend", TerminalControlState.INTERACTIVE, heldBy = "op-1", since = 1000L))))
                    send(Frame.Text(CommJson.encodeToString(AgentTerminalControlEvent.serializer(), AgentTerminalControlEvent("frontend", TerminalControlState.MEDIATED))))
                    send(Frame.Text(CommJson.encodeToString(AgentTerminalControlEvent.serializer(), AgentTerminalControlEvent("backend", TerminalControlState.MEDIATED)))) // handed back → holder cleared
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                val source = TerminalControlLiveSource(client, "ws://127.0.0.1:$port", token = "op")
                val events = withTimeout(10_000) { source.events().take(3) }
                // The holder id + since-time survive the wire (content-free but identity/time are carried).
                assertEquals(AgentTerminalControlEvent("backend", TerminalControlState.INTERACTIVE, heldBy = "op-1", since = 1000L), events[0])
                assertEquals(AgentTerminalControlEvent("frontend", TerminalControlState.MEDIATED), events[1])
                assertEquals(AgentTerminalControlEvent("backend", TerminalControlState.MEDIATED), events[2], "the delta hands backend back to mediated")
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }

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

/** Collect exactly [n] items from a (reconnecting/endless) flow, then cancel. */
private suspend fun <T> Flow<T>.take(n: Int): List<T> {
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
