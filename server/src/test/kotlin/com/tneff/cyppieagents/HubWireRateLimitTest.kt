package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Clock
import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.CapabilityRegistry
import com.tneff.cyppieagents.connector.ProviderRegistry
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus.AVAILABLE
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.ProviderInfo
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.WireAck
import com.tneff.cyppieagents.model.WireEnvelope
import com.tneff.cyppieagents.model.WireError
import com.tneff.cyppieagents.model.WireErrorCode
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * E2.5a / CYP-161 — the `/ws/hub` token-bucket rate limit. Pflicht axes RL1 · RL2 · RL5 · RC2 · RC3
 * (+ RL3 refill, RL4 flood-close), driven by a real Ktor WS client + a fake [Clock] (deterministic time).
 */
class HubWireRateLimitTest {

    private class FakeClock(var nowMs: Long = 0L) : Clock {
        override fun now(): Long = nowMs
    }

    private val provider = ProviderInfo("claude", "Claude")
    private fun allAvailable() = Capabilities(AVAILABLE, AVAILABLE, AVAILABLE, AVAILABLE, AVAILABLE, ConnectorKind.STREAM_JSON)
    private fun agents() = listOf(
        Agent("po", "PO", Role.PO, "po"),
        Agent("backend", "BE", Role.WORKER, "backend"),
        Agent("frontend", "FE", Role.WORKER, "frontend"),
    )
    private fun registry() = TokenRegistry(
        mapOf("tok-po" to "po", "tok-backend" to "backend", "tok-frontend" to "frontend"),
        operatorToken = "tok-op",
    )

    private class Fixture(val hub: Hub)

    private fun ApplicationTestBuilder.installWire(limiter: WireRateLimiter): Fixture {
        val hub = Hub(HubState.hubAndSpoke(agents(), HubState.OPERATOR_ID), InMemoryMessageStore())
        application { install(WebSockets); routing { hubWireRoutes(hub, registry(), CapabilityRegistry(), ProviderRegistry(), limiter, com.tneff.cyppieagents.connector.ConnectorSessions(), com.tneff.cyppieagents.events.EventRecorder(com.tneff.cyppieagents.events.InMemoryEventSink(com.tneff.cyppieagents.events.SystemTimeSource()), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)), { "default" }) } }
        return Fixture(hub)
    }

    private fun frame(f: WireFrame, v: Int = 1) = CommJson.encodeToString(WireEnvelope.serializer(), WireEnvelope(v, f))
    private suspend fun DefaultClientWebSocketSession.sendFrame(f: WireFrame) = send(Frame.Text(frame(f)))
    private suspend fun DefaultClientWebSocketSession.recv(): WireFrame =
        CommJson.decodeFromString<WireEnvelope>((incoming.receive() as Frame.Text).readText()).frame
    private suspend fun DefaultClientWebSocketSession.handshake() {
        sendFrame(WireHello(allAvailable(), provider)); assertIs<WireAck>(recv())
    }
    private fun wsClient(b: ApplicationTestBuilder) = b.createClient { install(ClientWebSockets) }

    // ---------- RL1 ⭐ throttle + fail-closed ----------
    @Test
    fun rl1_capacityThenThrottle_overLimitPostsNothing() = testApplication {
        val fx = installWire(WireRateLimiter(capacity = 3, refillPerSec = 5, clock = FakeClock())) // clock frozen → no refill
        wsClient(this).webSocket("/ws/hub?token=tok-po") {
            handshake()
            repeat(3) { sendFrame(WireSend("po-backend", "m$it")); assertIs<WireAck>(recv()) }
            sendFrame(WireSend("po-backend", "over"))
            assertEquals(WireErrorCode.RATE_LIMITED, assertIs<WireError>(recv()).code)
        }
        assertEquals(3, fx.hub.channelMessages("po", "po-backend").size, "exactly CAPACITY posted; the over-limit one posted nothing")
    }

    // ---------- RL2 per-agent isolation ----------
    @Test
    fun rl2_oneAgentFlood_doesNotThrottleAnother() = testApplication {
        installWire(WireRateLimiter(capacity = 1, refillPerSec = 5, clock = FakeClock()))
        val client = wsClient(this)
        client.webSocket("/ws/hub?token=tok-po") { // po empties its own bucket
            handshake()
            sendFrame(WireSend("po-backend", "a")); assertIs<WireAck>(recv())
            sendFrame(WireSend("po-backend", "b")); assertEquals(WireErrorCode.RATE_LIMITED, assertIs<WireError>(recv()).code)
        }
        client.webSocket("/ws/hub?token=tok-backend") { // backend's bucket is independent → still sends
            handshake()
            sendFrame(WireSend("po-backend", "c")); assertIs<WireAck>(recv())
        }
    }

    // ---------- RL5 ⭐ RC1: same agentId, TWO connections, ONE shared bucket ----------
    @Test
    fun rl5_sameAgentTwoConnections_shareOneBucket() = testApplication {
        installWire(WireRateLimiter(capacity = 2, refillPerSec = 5, clock = FakeClock()))
        val client = wsClient(this)
        client.webSocket("/ws/hub?token=tok-po") { // connection 1 consumes 1 of the 2 shared tokens
            handshake()
            sendFrame(WireSend("po-backend", "c1")); assertIs<WireAck>(recv())
        }
        client.webSocket("/ws/hub?token=tok-po") { // connection 2, SAME agent → shares the same bucket
            handshake()
            sendFrame(WireSend("po-backend", "c2-ok")); assertIs<WireAck>(recv()) // the 2nd (last) token
            sendFrame(WireSend("po-backend", "c2-over"))
            assertEquals(WireErrorCode.RATE_LIMITED, assertIs<WireError>(recv()).code, "N connections cannot multiply the budget")
        }
    }

    // ---------- RC2: close-counter is per-connection-local (fresh conn not insta-closed by a stale count) ----------
    @Test
    fun rc2_freshConnectionOfThrottledAgent_isNotInstaClosed() = testApplication {
        installWire(WireRateLimiter(capacity = 1, refillPerSec = 5, floodCloseAfter = 3, clock = FakeClock()))
        val client = wsClient(this)
        client.webSocket("/ws/hub?token=tok-po") { // conn1 racks up 2 rejects (< floodClose=3), stays open then we close
            handshake()
            sendFrame(WireSend("po-backend", "a")); assertIs<WireAck>(recv()) // bucket → 0
            sendFrame(WireSend("po-backend", "r1")); assertIs<WireError>(recv()) // reject 1
            sendFrame(WireSend("po-backend", "r2")); assertIs<WireError>(recv()) // reject 2 (still open)
        }
        client.webSocket("/ws/hub?token=tok-po") { // conn2: fresh counter; shared bucket still empty
            handshake()
            // first reject on conn2 → counter is FRESH (1), NOT 3 → still throttled but NOT closed.
            sendFrame(WireSend("po-backend", "r1")); assertEquals(WireErrorCode.RATE_LIMITED, assertIs<WireError>(recv()).code)
            // proof it stayed open: a SECOND reject is delivered (counter 2 < 3), not a close.
            sendFrame(WireSend("po-backend", "r2")); assertEquals(WireErrorCode.RATE_LIMITED, assertIs<WireError>(recv()).code)
        }
    }

    // ---------- RC3: bucket key is the bound agentId, NOT a frame value (per-agent, not per-channel) ----------
    @Test
    fun rc3_throttleIsPerAgent_notPerChannel() = testApplication {
        installWire(WireRateLimiter(capacity = 1, refillPerSec = 5, clock = FakeClock()))
        wsClient(this).webSocket("/ws/hub?token=tok-po") {
            handshake()
            sendFrame(WireSend("po-backend", "x")); assertIs<WireAck>(recv()) // agent bucket → 0
            // a DIFFERENT channel must NOT get a fresh bucket — the key is the agentId, not the channel.
            sendFrame(WireSend("po-frontend", "y"))
            assertEquals(WireErrorCode.RATE_LIMITED, assertIs<WireError>(recv()).code, "throttle is per-agent across channels")
        }
    }

    // ---------- RL3 refill (not a permanent lockout) ----------
    @Test
    fun rl3_refillAfterTimeAdvances() = testApplication {
        val clock = FakeClock()
        installWire(WireRateLimiter(capacity = 1, refillPerSec = 5, clock = clock))
        wsClient(this).webSocket("/ws/hub?token=tok-po") {
            handshake()
            sendFrame(WireSend("po-backend", "a")); assertIs<WireAck>(recv())
            sendFrame(WireSend("po-backend", "b")); assertIs<WireError>(recv()) // empty
            clock.nowMs = 1_000 // +1s → 5 tokens refilled (capped at capacity 1)
            sendFrame(WireSend("po-backend", "c")); assertIs<WireAck>(recv()) // accepted again
        }
    }

    // ---------- RL4 flood-close backstop (VIOLATED_POLICY) ----------
    @Test
    fun rl4_sustainedFlood_closesViolatedPolicy() = testApplication {
        installWire(WireRateLimiter(capacity = 1, refillPerSec = 5, floodCloseAfter = 2, clock = FakeClock()))
        wsClient(this).webSocket("/ws/hub?token=tok-po") {
            handshake()
            sendFrame(WireSend("po-backend", "a")); assertIs<WireAck>(recv()) // bucket → 0
            sendFrame(WireSend("po-backend", "r1")); assertIs<WireError>(recv()) // reject 1
            sendFrame(WireSend("po-backend", "r2")); assertIs<WireError>(recv()) // reject 2 = floodClose → close
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
    }
}
