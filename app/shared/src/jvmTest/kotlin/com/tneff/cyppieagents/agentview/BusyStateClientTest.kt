package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AgentBusyStateEvent
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
import kotlin.test.assertTrue

/**
 * CYP-324 — the client busy-state seam on the frozen contract.
 *  - [BusyStateLiveSource] decodes the REAL `:core` [AgentBusyStateEvent] off `/ws/busy-state` (snapshot then
 *    deltas) — no hand-parse.
 *  - [BusyStateViewModel] upserts by `agentId` (latest-wins, idempotent) into its map — a reconnect snapshot
 *    mid-turn re-delivers `busy = true` and is applied over the old value, never duplicated.
 */
class BusyStateClientTest {

    @Test
    fun liveSource_decodesRealEvents_snapshotThenDelta() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing {
                // The server shape: snapshot (one per agent) then a live delta, all as AgentBusyStateEvent frames.
                webSocket("/ws/busy-state") {
                    send(Frame.Text(CommJson.encodeToString(AgentBusyStateEvent.serializer(), AgentBusyStateEvent("backend", true))))
                    send(Frame.Text(CommJson.encodeToString(AgentBusyStateEvent.serializer(), AgentBusyStateEvent("frontend", false))))
                    send(Frame.Text(CommJson.encodeToString(AgentBusyStateEvent.serializer(), AgentBusyStateEvent("backend", false))))
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                val source = BusyStateLiveSource(client, "ws://127.0.0.1:$port", token = "op")
                val events = withTimeout(10_000) { source.events().take(3) }
                assertEquals(AgentBusyStateEvent("backend", true), events[0])
                assertEquals(AgentBusyStateEvent("frontend", false), events[1])
                assertEquals(AgentBusyStateEvent("backend", false), events[2], "the delta clears backend's busy")
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }

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
