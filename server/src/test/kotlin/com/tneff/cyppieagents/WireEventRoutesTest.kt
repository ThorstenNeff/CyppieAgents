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
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.ProviderInfo
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.WireEnvelope
import com.tneff.cyppieagents.model.WireError
import com.tneff.cyppieagents.model.WireErrorCode
import com.tneff.cyppieagents.model.WireEvent
import com.tneff.cyppieagents.model.WireEventType
import com.tneff.cyppieagents.model.WireFrame
import com.tneff.cyppieagents.model.WireHello
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
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** S5 / G4 — the server-enforced `WireEvent` ingress at `/ws/hub`: Hello-required (G4-1), recorded into the
 *  Event-Log attributed + source-stamped (G4-3/5 end-to-end), and an unknown frame is bounded-closed (the
 *  v1-compat pin: a new sealed subtype makes an old decoder fail — the server must close gracefully, not crash). */
class WireEventRoutesTest {

    private val provider = ProviderInfo("claude", "Claude")
    private fun remoteCaps() = Capabilities(
        CapabilityStatus.UNAVAILABLE, CapabilityStatus.LIMITED, CapabilityStatus.LIMITED,
        CapabilityStatus.LIMITED, CapabilityStatus.AVAILABLE, ConnectorKind.STREAM_JSON,
    )
    private fun registry() = TokenRegistry(mapOf("tok-backend" to "backend"), operatorToken = "tok-op")

    private class Fx(val sink: InMemoryEventSink)

    private fun ApplicationTestBuilder.installWire(): Fx {
        val hub = Hub(HubState.hubAndSpoke(listOf(Agent("po", "PO", Role.PO, "po"), Agent("backend", "BE", Role.WORKER, "backend")), HubState.OPERATOR_ID), InMemoryMessageStore())
        val sink = InMemoryEventSink(SystemTimeSource())
        val recorder = EventRecorder(sink, CoroutineScope(Dispatchers.Default + SupervisorJob())).also { it.start() }
        application { install(WebSockets); routing { hubWireRoutes(hub, registry(), CapabilityRegistry(), ProviderRegistry(), WireRateLimiter(), ConnectorSessions(), recorder, { "default" }) } }
        return Fx(sink)
    }

    private fun frame(f: WireFrame) = CommJson.encodeToString(WireEnvelope.serializer(), WireEnvelope(1, f))
    private suspend fun DefaultClientWebSocketSession.sendFrame(f: WireFrame) = send(Frame.Text(frame(f)))
    private suspend fun DefaultClientWebSocketSession.recv(): WireFrame =
        CommJson.decodeFromString<WireEnvelope>((incoming.receive() as Frame.Text).readText()).frame
    private fun wsClient(b: ApplicationTestBuilder) = b.createClient { install(ClientWebSockets) }

    /** G4-1 — a WireEvent before WireHello is a PROTOCOL violation (fail-closed). */
    @Test
    fun event_beforeHello_isProtocolReject() = testApplication {
        installWire()
        wsClient(this).webSocket("/ws/hub?token=tok-backend") {
            sendFrame(WireEvent(WireEventType.TOOL_CALL, tool = "Bash"))
            val r = recv()
            assertIs<WireError>(r)
            assertEquals(WireErrorCode.PROTOCOL, (r as WireError).code)
        }
    }

    /** Happy path — after Hello, a WireEvent is recorded into the Event-Log, attributed to the bound agent,
     *  source-stamped `remote` (G4-3/G4-5 end-to-end). */
    @Test
    fun event_afterHello_isRecorded_attributed_sourceRemote() = testApplication {
        val fx = installWire()
        wsClient(this).webSocket("/ws/hub?token=tok-backend") {
            sendFrame(WireHello(remoteCaps(), provider))
            assertIs<com.tneff.cyppieagents.model.WireAck>(recv()) // hello ack
            sendFrame(WireEvent(WireEventType.TOOL_CALL, tool = "Bash")) // fire-and-forget (no ack)
            withTimeout(3000) {
                while (fx.sink.all().none {
                        it.type == EventType.TOOL_CALL && it.agentId == "backend" && it.detail["source"]?.jsonPrimitive?.content == "remote"
                    }) delay(10)
            }
        }
    }

    /** v1-compat — an UNKNOWN frame type makes the decoder fail; the server bounded-closes (PROTOCOL_ERROR),
     *  it does NOT crash/hang (the `/ws/hub` versioned boundary). */
    @Test
    fun unknownFrameType_isBoundedClose_notCrash() = testApplication {
        installWire()
        wsClient(this).webSocket("/ws/hub?token=tok-backend") {
            send(Frame.Text("""{"v":1,"frame":{"type":"totally_unknown_frame"}}""")) // unknown discriminator
            val r = recv()
            assertIs<WireError>(r)
            assertEquals(WireErrorCode.BAD_REQUEST, (r as WireError).code)
            assertEquals(CloseReason.Codes.PROTOCOL_ERROR.code, closeReason.await()?.code)
        }
    }
}
