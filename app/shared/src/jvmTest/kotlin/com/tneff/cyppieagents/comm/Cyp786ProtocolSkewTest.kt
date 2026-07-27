package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.net.Backoff
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket as serverWebSocket

/**
 * CYP-786 — an undecodable `/ws/comm` frame (app-schema SKEW: an unknown discriminator or a missing required field)
 * must surface as a TERMINAL, named [CommLiveEvent.ProtocolSkew] — never the opaque `SerializationException` →
 * `Disconnected` → `.reconnecting()` churn under a generic offline banner (the dogfood blocker). Two levels:
 *
 * 1. [skewFrame_classifiesAsTerminalProtocolSkew_notDisconnectedChurn] — the decode-classification at
 *    [CommWsClient] (e2e over a real socket): a malformed frame → ProtocolSkew as the FINAL event, NOT Disconnected.
 * 2. [protocolSkew_isTerminal_doesNotReconnect_setsOwnState] — the VM makes it terminal (cancels the collector so
 *    the reconnect loop does not replay the same undecodable frame) + surfaces its own named state.
 */
class Cyp786ProtocolSkewTest {

    // ---- 1. decode-classification (e2e, real socket) — twin of CommWsClientE2eTest ----

    @Test
    fun skewFrame_classifiesAsTerminalProtocolSkew_notDisconnectedChurn() = runBlocking {
        // A well-formed JSON frame with an unknown discriminator — exactly a client that meets a server event type it
        // does not know (a version skew). CommJson decode throws SerializationException → the client must classify it.
        val skewFrame = "{\"type\":\"__cyp786_future_server_event__\"}"

        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing {
                serverWebSocket("/ws/comm") {
                    send(Frame.Text(skewFrame))
                    close()
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                val ws = CommWsClient(client, "ws://127.0.0.1:$port", token = "op")
                val events = withTimeout(10_000) { ws.events().toList() }

                // Connected, then ProtocolSkew as the TERMINAL event — and NO trailing Disconnected (which would
                // reconnect-churn). MUT: remove the try/catch(SerializationException) at CommWsClient's decode → the
                // exception unwinds to the outer catch → logWsError → Disconnected → this reds (no ProtocolSkew).
                assertEquals(2, events.size, "Connected + terminal ProtocolSkew (no Disconnected): $events")
                assertTrue(events[0] is CommLiveEvent.Connected, "first event is Connected: $events")
                assertTrue(events[1] is CommLiveEvent.ProtocolSkew, "a skew frame must classify as ProtocolSkew: $events")
                assertNull(
                    events.firstOrNull { it is CommLiveEvent.Disconnected },
                    "a skew is TERMINAL — it must NOT be followed by Disconnected (the reconnect-churn path): $events",
                )
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }

    // ---- 2. VM terminal handling + own state — twin of CommRevokeTerminationTest ----

    private class EmptyApi : CommApi {
        override suspend fun channels(): List<Channel> = emptyList()
        override suspend fun agents(): List<Agent> = emptyList()
        override suspend fun messages(channelId: String, since: Long?): List<Message> = emptyList()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?): Message =
            Message("x", channelId, "operator", body, 1L)
    }

    /** Connected → ProtocolSkew → complete, each subscription. The fix cancels the collector → subs stays 1. */
    private class SkewingCommSource : CommLiveSource {
        val subscriptions = MutableStateFlow(0)
        override fun events(): Flow<CommLiveEvent> = flow {
            subscriptions.value += 1
            emit(CommLiveEvent.Connected)
            emit(CommLiveEvent.ProtocolSkew("Field 'delivered' is required"))
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun protocolSkew_isTerminal_doesNotReconnect_setsOwnState() = runTest {
        val source = SkewingCommSource()
        val vm = CommViewModel(
            EmptyApi(), source, viewerId = "operator",
            backoff = Backoff(initialMs = 1, maxMs = 1), scope = backgroundScope,
        )
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()

        // Terminal: a skew never resolves without a deploy → no reconnect loop. MUT: drop `liveJob?.cancel()` on
        // ProtocolSkew in collectLive → `.reconnecting()` re-subscribes and replays the same skew → subs climbs → reds.
        assertEquals(1, source.subscriptions.value, "ProtocolSkew is terminal — subscriptions>1 means a reconnect LOOP")
        // Own named state (mirrors accessRevoked, CYP-819 D2), NOT folded into a silent offline. MUT: drop the
        // `_state.update{ protocolSkew = true }` → this reds.
        assertTrue(vm.state.value.protocolSkew, "a skew must set its own named state, not a silent offline")
        assertEquals("Field 'delivered' is required", vm.state.value.protocolSkewDetail)
    }
}
