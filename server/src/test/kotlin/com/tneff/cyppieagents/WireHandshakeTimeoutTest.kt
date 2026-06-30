package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.CapabilityRegistry
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.connector.ProviderRegistry
import com.tneff.cyppieagents.events.EventRecorder
import com.tneff.cyppieagents.events.InMemoryEventSink
import com.tneff.cyppieagents.events.SystemTimeSource
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.ProviderInfo
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.WireAck
import com.tneff.cyppieagents.model.WireEnvelope
import com.tneff.cyppieagents.model.WireFrame
import com.tneff.cyppieagents.model.WireHello
import com.tneff.cyppieagents.model.WireSend
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.WireRateLimiter
import com.tneff.cyppieagents.routing.hubWireRoutes
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * CYP-173 — the slow-loris reap. An authenticated `/ws/hub` connection that never completes the WireHello
 * handshake is closed after `helloTimeoutMs` (else each such socket pins a coroutine + connection forever);
 * a connection that DOES handshake in time is never reaped.
 */
class WireHandshakeTimeoutTest {

    private val provider = ProviderInfo("claude", "Claude")
    private fun remoteCaps() = Capabilities(
        CapabilityStatus.UNAVAILABLE, CapabilityStatus.LIMITED, CapabilityStatus.LIMITED,
        CapabilityStatus.LIMITED, CapabilityStatus.AVAILABLE, ConnectorKind.STREAM_JSON,
    )
    private fun registry() = TokenRegistry(mapOf("tok-backend" to "backend"), operatorToken = "tok-op")

    private fun ApplicationTestBuilder.installWire(helloTimeoutMs: Long) {
        val hub = Hub(HubState.hubAndSpoke(listOf(Agent("po", "PO", Role.PO, "po"), Agent("backend", "BE", Role.WORKER, "backend")), HubState.OPERATOR_ID), InMemoryMessageStore())
        val recorder = EventRecorder(InMemoryEventSink(SystemTimeSource()), CoroutineScope(Dispatchers.Default + SupervisorJob())).also { it.start() }
        application { install(WebSockets); routing { hubWireRoutes(hub, registry(), CapabilityRegistry(), ProviderRegistry(), WireRateLimiter(), ConnectorSessions(), recorder, { "default" }, helloTimeoutMs) } }
    }

    private fun frame(f: WireFrame) = CommJson.encodeToString(WireEnvelope.serializer(), WireEnvelope(1, f))
    private suspend fun DefaultClientWebSocketSession.sendFrame(f: WireFrame) = send(Frame.Text(frame(f)))
    private suspend fun DefaultClientWebSocketSession.recv(): WireFrame =
        CommJson.decodeFromString<WireEnvelope>((incoming.receive() as Frame.Text).readText()).frame
    private fun wsClient(b: ApplicationTestBuilder) = b.createClient { install(ClientWebSockets) }

    /** Reap: authenticate, send NO WireHello → closed VIOLATED_POLICY within the deadline. */
    @Test
    fun authenticatedButNoHello_isReapedAfterTimeout() = testApplication {
        installWire(helloTimeoutMs = 200)
        wsClient(this).webSocket("/ws/hub?token=tok-backend") {
            // never send a Hello — the read-loop would otherwise block on `incoming` forever.
            val code = withTimeout(5000) { closeReason.await()?.code }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, code, "a never-handshook connection is reaped")
        }
    }

    /** No false-reap: handshake in time → the watchdog stands down; the connection lives past the deadline. */
    @Test
    fun handshookInTime_isNotReaped() = testApplication {
        installWire(helloTimeoutMs = 200)
        wsClient(this).webSocket("/ws/hub?token=tok-backend") {
            sendFrame(WireHello(remoteCaps(), provider))
            assertIs<WireAck>(recv())
            delay(500) // well past the 200ms deadline — the reaper must NOT fire
            sendFrame(WireSend("po-backend", "still alive"))
            assertIs<WireAck>(recv()) // a response proves the connection was not reaped
        }
    }
}
