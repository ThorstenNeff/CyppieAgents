package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.CapabilityRegistry
import com.tneff.cyppieagents.connector.ProviderRegistry
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus.AVAILABLE
import com.tneff.cyppieagents.model.CapabilityStatus.LIMITED
import com.tneff.cyppieagents.model.CapabilityStatus.UNAVAILABLE
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.ProviderInfo
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.WireAck
import com.tneff.cyppieagents.model.WireEnvelope
import com.tneff.cyppieagents.model.WireError
import com.tneff.cyppieagents.model.WireErrorCode
import com.tneff.cyppieagents.model.WireFrame
import com.tneff.cyppieagents.model.WireHello
import com.tneff.cyppieagents.model.WireMessage
import com.tneff.cyppieagents.model.WireSend
import com.tneff.cyppieagents.model.WireSubscribe
import com.tneff.cyppieagents.routing.MessageInput
import com.tneff.cyppieagents.routing.TokenRegistry
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * E2.2 / CYP-138 — the `/ws/hub` external wire, driven by a real Ktor WS test-client (the BYOA stand-in).
 * Each Pflicht axis is an exact assertion; the reddening mutation is named in the doc above it.
 */
class HubWireRoutesTest {

    private val provider = ProviderInfo("claude", "Claude")
    private fun allAvailable() = Capabilities(AVAILABLE, AVAILABLE, AVAILABLE, AVAILABLE, AVAILABLE, ConnectorKind.STREAM_JSON)

    // 3 agents so an existing-but-forbidden channel exists (backend is NOT a member of po-frontend) → M2.
    private fun agents() = listOf(
        Agent("po", "PO", Role.PO, "po"),
        Agent("backend", "BE", Role.WORKER, "backend"),
        Agent("frontend", "FE", Role.WORKER, "frontend"),
    )
    private fun registry() = TokenRegistry(
        mapOf("tok-po" to "po", "tok-backend" to "backend", "tok-frontend" to "frontend"),
        operatorToken = "tok-op",
    )

    private class Fixture(val hub: Hub, val store: InMemoryMessageStore, val caps: CapabilityRegistry, val prov: ProviderRegistry)

    private fun ApplicationTestBuilder.installWire(): Fixture {
        val store = InMemoryMessageStore()
        val hub = Hub(HubState.hubAndSpoke(agents(), HubState.OPERATOR_ID), store)
        val caps = CapabilityRegistry()
        val prov = ProviderRegistry()
        // A default limiter (capacity 20) never throttles these few-send E2.2 tests; CYP-161 rate-limit
        // behavior is exercised in HubWireRateLimitTest with a fake Clock.
        application { install(WebSockets); routing { hubWireRoutes(hub, registry(), caps, prov, com.tneff.cyppieagents.routing.WireRateLimiter()) } }
        return Fixture(hub, store, caps, prov)
    }

    private fun frame(f: WireFrame, v: Int = 1) = CommJson.encodeToString(WireEnvelope.serializer(), WireEnvelope(v, f))
    private suspend fun DefaultClientWebSocketSession.sendFrame(f: WireFrame, v: Int = 1) = send(Frame.Text(frame(f, v)))
    private suspend fun DefaultClientWebSocketSession.recv(): WireFrame =
        CommJson.decodeFromString<WireEnvelope>((incoming.receive() as Frame.Text).readText()).frame
    private fun wsClient(builder: ApplicationTestBuilder) = builder.createClient { install(ClientWebSockets) }

    // ---------- happy path: auth + version + hello-clamp + send (the spine) ----------
    @Test
    fun helloThenSend_handshakeAcksAndSendPostsAsBoundAgent() = testApplication {
        val fx = installWire()
        wsClient(this).webSocket("/ws/hub?token=tok-po") {
            sendFrame(WireHello(allAvailable(), provider))
            assertIs<WireAck>(recv())
            sendFrame(WireSend("po-backend", "delegate this"))
            assertIs<WireAck>(recv())
        }
        val posted = fx.hub.channelMessages("po", "po-backend")
        assertEquals(1, posted.size)
        assertEquals("po", posted.single().from) // A1: from is the bound agent (no frame field)
        assertTrue(posted.single().body.contains("delegate this"))
    }

    // ---------- AC-auth: no/operator token → close VIOLATED_POLICY before any frame ----------
    // Mutation: drop the agentFor null-check → unauth/operator connects & sends → this reddens.
    @Test
    fun noToken_and_operatorToken_areClosedBeforeAnyFrame() = testApplication {
        installWire()
        val client = wsClient(this)
        client.webSocket("/ws/hub") { // no token
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
        client.webSocket("/ws/hub?token=tok-op") { // operator token: agentFor==null → only agents emit
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
    }

    // ---------- AC-version: unsupported version → WireError + close, fail-closed ----------
    // Mutation: drop the SUPPORTED_WIRE_VERSIONS gate → v=999 accepted → this reddens.
    @Test
    fun unsupportedVersion_isRejectedFailClosed() = testApplication {
        installWire()
        wsClient(this).webSocket("/ws/hub?token=tok-po") {
            sendFrame(WireHello(allAvailable(), provider), v = 999)
            assertEquals(WireErrorCode.UNSUPPORTED_VERSION, assertIs<WireError>(recv()).code)
            assertEquals(CloseReason.Codes.PROTOCOL_ERROR.code, closeReason.await()?.code)
        }
    }

    // ---------- R2a ⭐ FO#1: the handshake clamps to the REMOTE ceiling (the ONLY caps-ingress) ----------
    // Mutations: skip clamp (record hello.caps) OR ceilingFor(LOCAL) → liar recorded full AVAILABLE → reddens.
    @Test
    fun helloAllAvailable_recordsRemoteClampedCaps_notTheLie() = testApplication {
        val fx = installWire()
        wsClient(this).webSocket("/ws/hub?token=tok-po") {
            sendFrame(WireHello(allAvailable(), provider))
            assertIs<WireAck>(recv())
        }
        val recorded = fx.caps.get("po")!!
        assertEquals(UNAVAILABLE, recorded.structuredUsage, "billing-critical dim clamped OFF despite the AVAILABLE claim")
        assertEquals(LIMITED, recorded.toolGranularity)
        assertEquals(LIMITED, recorded.reliableResult)
        assertEquals(LIMITED, recorded.rateLimitSignal)
        assertEquals(AVAILABLE, recorded.coordination, "the verifiable dim stays AVAILABLE")
        assertEquals(provider, fx.prov.get("po"))
    }

    // R2a positive control: an honest in-ceiling reduction is preserved (clamp is most-restrictive, not constant).
    @Test
    fun helloHonestReduction_isPreserved() = testApplication {
        val fx = installWire()
        // declare coordination UNAVAILABLE (below the REMOTE ceiling's AVAILABLE) → must stay UNAVAILABLE.
        val honest = Capabilities(UNAVAILABLE, LIMITED, LIMITED, LIMITED, UNAVAILABLE, ConnectorKind.STREAM_JSON)
        wsClient(this).webSocket("/ws/hub?token=tok-po") {
            sendFrame(WireHello(honest, provider)); assertIs<WireAck>(recv())
        }
        assertEquals(UNAVAILABLE, fx.caps.get("po")!!.coordination, "honest reduction below the ceiling is respected")
    }

    // ---------- R2b: trust is STRUCTURAL REMOTE — a frame can't claim LOCAL to widen the ceiling ----------
    // (ignoreUnknownKeys lets a stray "trust":"LOCAL" decode; the ceiling is a literal REMOTE regardless.)
    @Test
    fun strayTrustFieldInHello_isIgnored_stillRemoteClamped() = testApplication {
        val fx = installWire()
        val rogueHello = """{"v":1,"frame":{"type":"hello","trust":"LOCAL","capabilities":{"structuredUsage":"available","toolGranularity":"available","reliableResult":"available","rateLimitSignal":"available","coordination":"available","kind":"stream_json"},"provider":{"id":"claude","displayName":"Claude"}}}"""
        wsClient(this).webSocket("/ws/hub?token=tok-po") {
            send(Frame.Text(rogueHello)); assertIs<WireAck>(recv())
        }
        assertEquals(UNAVAILABLE, fx.caps.get("po")!!.structuredUsage, "a LOCAL claim in a frame cannot widen the REMOTE ceiling")
    }

    // ---------- R3: unknown frame type / malformed envelope → WireError + close, fail-closed ----------
    // Mutation: swallow the decode failure (continue) instead of WireError+close → this reddens.
    @Test
    fun unknownFrameType_isRejectedFailClosed_notSilentlyDropped() = testApplication {
        installWire()
        wsClient(this).webSocket("/ws/hub?token=tok-po") {
            send(Frame.Text("""{"v":1,"frame":{"type":"bogus_frame"}}"""))
            assertEquals(WireErrorCode.BAD_REQUEST, assertIs<WireError>(recv()).code)
            assertEquals(CloseReason.Codes.PROTOCOL_ERROR.code, closeReason.await()?.code)
        }
    }

    // R3 ordering: a Send before the handshake is a defined PROTOCOL rejection (no pre-Hello write).
    @Test
    fun sendBeforeHello_isProtocolRejected() = testApplication {
        val fx = installWire()
        wsClient(this).webSocket("/ws/hub?token=tok-po") {
            sendFrame(WireSend("po-backend", "too early"))
            assertEquals(WireErrorCode.PROTOCOL, assertIs<WireError>(recv()).code)
            assertEquals(CloseReason.Codes.PROTOCOL_ERROR.code, closeReason.await()?.code)
        }
        assertTrue(fx.hub.channelMessages("po", "po-backend").isEmpty(), "nothing posted before the handshake")
    }

    // ---------- M1 (PFLICHT): a second Hello is DEFINED — here: reject (one handshake per connection) ----------
    // Mutation: drop the `handshook` guard → second Hello re-handshakes (a 2nd caps set) → this reddens.
    @Test
    fun secondHello_isRejected_notASecondSet() = testApplication {
        installWire()
        wsClient(this).webSocket("/ws/hub?token=tok-po") {
            sendFrame(WireHello(allAvailable(), provider)); assertIs<WireAck>(recv())
            sendFrame(WireHello(allAvailable(), provider))
            assertEquals(WireErrorCode.PROTOCOL, assertIs<WireError>(recv()).code)
            assertEquals(CloseReason.Codes.PROTOCOL_ERROR.code, closeReason.await()?.code)
        }
    }

    // ---------- M2: WireError(403) uniform — unknown channel == existing-but-forbidden channel ----------
    // Mutation: branch a distinct code/detail for the unknown case → the two diverge → this reddens.
    @Test
    fun forbiddenAndUnknownChannel_yieldTheSameForbiddenShape() = testApplication {
        val fx = installWire()
        wsClient(this).webSocket("/ws/hub?token=tok-backend") {
            sendFrame(WireHello(allAvailable(), provider)); assertIs<WireAck>(recv())
            // existing channel backend is NOT a member of (po-frontend) → forbidden
            sendFrame(WireSend("po-frontend", "x"))
            val forbidden = assertIs<WireError>(recv())
            // a channel that does not exist at all → unknown
            sendFrame(WireSend("ghost-channel", "y"))
            val unknown = assertIs<WireError>(recv())
            assertEquals(WireErrorCode.FORBIDDEN, forbidden.code)
            assertEquals(WireErrorCode.FORBIDDEN, unknown.code, "unknown channel must not reveal itself with a distinct code")
        }
        assertTrue(fx.store.byChannel("po-frontend").isEmpty() && fx.store.byChannel("ghost-channel").isEmpty())
    }

    // ---------- A5: oversized Send body → WireError(TOO_LARGE), nothing posted ----------
    // Mutation: remove MessageInput.requireValidBody at the wire edge → giant accepted → this reddens.
    @Test
    fun oversizedSend_isRejected_nothingPosted() = testApplication {
        val fx = installWire()
        wsClient(this).webSocket("/ws/hub?token=tok-po") {
            sendFrame(WireHello(allAvailable(), provider)); assertIs<WireAck>(recv())
            sendFrame(WireSend("po-backend", "x".repeat(MessageInput.MAX_BODY_CHARS + 1)))
            assertEquals(WireErrorCode.TOO_LARGE, assertIs<WireError>(recv()).code)
        }
        assertTrue(fx.hub.channelMessages("po", "po-backend").isEmpty())
    }

    // ---------- R1 ⭐ SECURITY: Subscribe funnels visibleMessages — no cross-project leak ----------
    // The push reuses Hub.channelMessages verbatim. Reddening mutation lives on that guard
    // (Hub.kt: visibleMessages → store.byChannel) → the FOREIGN needle would stream → this reddens.
    @Test
    fun subscribe_doesNotLeakAForeignProjectMessageOnAReusedChannelId() = testApplication {
        val fx = installWire()
        val inProjectNeedle = "INPROJECT-needle-7b3c"
        val foreignNeedle = "FOREIGN-needle-9f1a"
        // Same channelId "po-backend" carries one active-project message AND one foreign-project message.
        fx.store.append(Message("m-in", "po-backend", "po", inProjectNeedle, ts = 1)) // projectId defaults = active
        fx.store.append(Message("m-foreign", "po-backend", "backend", foreignNeedle, ts = 2, projectId = "projB"))

        val bodies = mutableListOf<String>()
        wsClient(this).webSocket("/ws/hub?token=tok-po") {
            sendFrame(WireHello(allAvailable(), provider)); assertIs<WireAck>(recv())
            sendFrame(WireSubscribe(listOf("po-backend")))
            // drain until the Ack that terminates the subscribe response
            while (true) {
                when (val f = recv()) {
                    is WireMessage -> bodies.add(f.message.body)
                    is WireAck -> break
                    else -> error("unexpected frame $f")
                }
            }
        }
        // content-pre-guard: the in-project needle IS delivered (the stream is live & would carry a leak)
        assertTrue(bodies.any { it.contains(inProjectNeedle) }, "in-project message is delivered (pre-guard)")
        // the actual security assertion: the foreign-project needle is ABSENT
        assertTrue(bodies.none { it.contains(foreignNeedle) }, "a foreign-project message on a reused channelId must NOT leak")
    }

    // R1 canRead gate: subscribing a channel the agent may not read yields no messages (only the Ack).
    @Test
    fun subscribe_toAForbiddenChannel_streamsNothing() = testApplication {
        val fx = installWire()
        fx.store.append(Message("m1", "po-frontend", "frontend", "secret", ts = 1))
        var messages = 0
        wsClient(this).webSocket("/ws/hub?token=tok-backend") { // backend cannot read po-frontend
            sendFrame(WireHello(allAvailable(), provider)); assertIs<WireAck>(recv())
            sendFrame(WireSubscribe(listOf("po-frontend")))
            while (true) {
                when (val f = recv()) {
                    is WireMessage -> messages++
                    is WireAck -> break
                    else -> error("unexpected $f")
                }
            }
        }
        assertEquals(0, messages, "no message from a channel the subscriber may not read")
    }
}
