package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.CommJson
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
import com.tneff.cyppieagents.model.WireMessage
import com.tneff.cyppieagents.model.WireSend
import com.tneff.cyppieagents.model.WireSubscribe
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CYP-139 / E2.3 — **external Hub-Wire-Protocol conformance** against the FULLY-WIRED, served stack
 * ([E2ePlatform] → `installPlatform`: auth + version + rate-limit + all registries). Unlike the white-box
 * [com.tneff.cyppieagents.HubWireRoutesTest] (installs `hubWireRoutes` in isolation, peeks `hub.*`), this is
 * **black-box**: a pure WS client asserts ONLY wire-observable behavior — `WireAck`/`WireMessage`/`WireError`
 * frames + close codes — and verifies a **send by a receive** (a subscribed client reads it back), never by
 * peeking the store. It is (a) the runnable spec a BYOA connector author reads, and (b) the guard that catches
 * **wiring** regressions the isolated route test can't (e.g. `/ws/hub` not mounted, auth/limiter not wired).
 *
 * Reviewer-REQUIRED closes baked in:
 *  - **RC1 ⭐ pull-model timing:** `WireSubscribe` is a ONE-SHOT ACL-filtered history dump + `Ack`, NOT a
 *    live push (live-tail deferred to E2.5). So every round-trip is ordered **send-FIRST, then the reader
 *    subscribes and drains the history** — never subscribe-then-await-a-live-frame (which would hang).
 *  - **RC2 C7 isolation:** the rate-limit case runs on its OWN [E2ePlatform] so the shared per-agentId
 *    `WireRateLimiter` bucket starts full and other cases' sends don't drain it.
 *  - **RC3 bounded awaits:** every frame/close read is wrapped in [withTimeout] — a regression that stops
 *    honoring the contract **reddens** (timeout) instead of hanging the test (the E2.2 review saw a 73s hang).
 */
class WireConformanceTest {

    private val provider = ProviderInfo("claude", "Claude")
    private fun allAvailable() = Capabilities(AVAILABLE, AVAILABLE, AVAILABLE, AVAILABLE, AVAILABLE, ConnectorKind.STREAM_JSON)

    /** Active project: one PO + two workers → hub-and-spoke channels `po-backend` [po,backend] and `po-frontend` [po,frontend]. */
    private fun platform() = e2ePlatform(
        listOf(SeedProject("projA", agents = listOf(SeedAgent("po", Role.PO), SeedAgent("backend"), SeedAgent("frontend")))),
    )

    private val await = 5_000L // RC3: every wire read is bounded — a non-honoring server reddens, never hangs.

    private fun frame(f: WireFrame, v: Int = 1) = CommJson.encodeToString(WireEnvelope.serializer(), WireEnvelope(v, f))
    private suspend fun DefaultClientWebSocketSession.sendFrame(f: WireFrame, v: Int = 1) = send(Frame.Text(frame(f, v)))
    private suspend fun DefaultClientWebSocketSession.recv(): WireFrame =
        withTimeout(await) { CommJson.decodeFromString<WireEnvelope>((incoming.receive() as Frame.Text).readText()).frame }
    private suspend fun DefaultClientWebSocketSession.awaitCloseCode(): Short? =
        withTimeout(await) { closeReason.await()?.code }

    /** Handshake (must be first): `Hello` → `Ack`. */
    private suspend fun DefaultClientWebSocketSession.handshake() {
        sendFrame(WireHello(allAvailable(), provider))
        assertIs<WireAck>(recv())
    }

    /** Drain a subscribe response (the RC1 history dump): message bodies until the terminating `Ack`. */
    private suspend fun DefaultClientWebSocketSession.drainSubscribeBodies(): List<String> {
        val bodies = mutableListOf<String>()
        while (true) when (val f = recv()) {
            is WireMessage -> bodies.add(f.message.body)
            is WireAck -> break
            else -> error("unexpected frame in subscribe response: $f")
        }
        return bodies
    }

    // ---------- C1: connect + auth ----------
    // Mutation: drop the agentFor null-check (allow-all) → the no-token client handshakes → C1-negative reddens.
    @Test
    fun c1_connectAndAuth() = runBlocking {
        platform().use { p ->
            // positive: a valid agent token completes the handshake
            p.asAgent("po").use { c -> c.webSocket("${p.wsBaseUrl}/ws/hub") { handshake() } }
            // negative: no token → closed VIOLATED_POLICY before any frame
            p.client(null).use { c ->
                c.webSocket("${p.wsBaseUrl}/ws/hub") {
                    assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, awaitCloseCode())
                }
            }
            // negative: an operator token is NOT an agent (only agents emit) → VIOLATED_POLICY
            p.asOperator().use { c ->
                c.webSocket("${p.wsBaseUrl}/ws/hub") {
                    assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, awaitCloseCode())
                }
            }
        }
    }

    // ---------- C2: version gate ----------
    // Mutation: drop the SUPPORTED_WIRE_VERSIONS gate → v=999 gets an Ack → C2-negative reddens.
    @Test
    fun c2_versionGate() = runBlocking {
        platform().use { p ->
            // positive: v=1 hello → Ack
            p.asAgent("po").use { c -> c.webSocket("${p.wsBaseUrl}/ws/hub") { handshake() } }
            // negative: v=999 → WireError(UNSUPPORTED_VERSION) + close PROTOCOL_ERROR
            p.asAgent("po").use { c ->
                c.webSocket("${p.wsBaseUrl}/ws/hub") {
                    sendFrame(WireHello(allAvailable(), provider), v = 999)
                    assertEquals(WireErrorCode.UNSUPPORTED_VERSION, assertIs<WireError>(recv()).code)
                    assertEquals(CloseReason.Codes.PROTOCOL_ERROR.code, awaitCloseCode())
                }
            }
        }
    }

    // ---------- C3: send → receive (round-trip; the E2.3 wiring guard) ----------
    // RC1: A sends FIRST, THEN B subscribes and reads the history (pull model, no live push).
    // Mutation: unmount `/ws/hub` from installPlatform → no round-trip → C3 reddens (a mount the isolated test can't catch).
    @Test
    fun c3_sendThenReceive() = runBlocking {
        platform().use { p ->
            val needle = "C3-roundtrip-7b3c"
            p.asAgent("po").use { c ->
                c.webSocket("${p.wsBaseUrl}/ws/hub") {
                    handshake()
                    sendFrame(WireSend("po-backend", needle))
                    assertIs<WireAck>(recv())
                }
            }
            p.asAgent("backend").use { c ->
                c.webSocket("${p.wsBaseUrl}/ws/hub") {
                    handshake()
                    sendFrame(WireSubscribe(listOf("po-backend")))
                    val bodies = drainSubscribeBodies()
                    assertTrue(bodies.any { it.contains(needle) }, "B receives A's body over the wire (send verified by receive)")
                }
            }
        }
    }

    // ---------- C4: 403 without write right (R1, re-pinned externally) ----------
    // Mutation: Subscribe via raw store.byChannel (drop visibleMessages) → the forbidden body leaks to a reader → C4 reddens.
    @Test
    fun c4_forbiddenWithoutWriteRight() = runBlocking {
        platform().use { p ->
            val allowed = "C4-allowed-a1f0"
            val forbidden = "C4-forbidden-b2e9"
            // pre-guard (delivery works): po MAY write po-frontend → Ack, and frontend will read it back.
            p.asAgent("po").use { c ->
                c.webSocket("${p.wsBaseUrl}/ws/hub") {
                    handshake(); sendFrame(WireSend("po-frontend", allowed)); assertIs<WireAck>(recv())
                }
            }
            // negative: backend is NOT a member of po-frontend → WireError(FORBIDDEN), nothing posted.
            p.asAgent("backend").use { c ->
                c.webSocket("${p.wsBaseUrl}/ws/hub") {
                    handshake(); sendFrame(WireSend("po-frontend", forbidden))
                    assertEquals(WireErrorCode.FORBIDDEN, assertIs<WireError>(recv()).code)
                }
            }
            // frontend reads po-frontend history: the allowed body IS present (pre-guard → absence is non-vacuous),
            // the forbidden body is ABSENT (a denied send never reaches a subscriber).
            p.asAgent("frontend").use { c ->
                c.webSocket("${p.wsBaseUrl}/ws/hub") {
                    handshake(); sendFrame(WireSubscribe(listOf("po-frontend")))
                    val bodies = drainSubscribeBodies()
                    assertTrue(bodies.any { it.contains(allowed) }, "pre-guard: an allowed send IS delivered")
                    assertTrue(bodies.none { it.contains(forbidden) }, "a forbidden send never reaches a subscriber")
                }
            }
        }
    }

    // ---------- C5: fail-closed framing ----------
    // Mutation: swallow the decode failure (continue) → no WireError/close → C5 reddens.
    @Test
    fun c5_failClosedFraming() = runBlocking {
        platform().use { p ->
            p.asAgent("po").use { c ->
                c.webSocket("${p.wsBaseUrl}/ws/hub") {
                    handshake() // positive: a well-formed frame is processed
                    // negative: an unknown frame type → WireError(BAD_REQUEST) + close (never silent-drop / hang)
                    send(Frame.Text("""{"v":1,"frame":{"type":"bogus_frame"}}"""))
                    assertEquals(WireErrorCode.BAD_REQUEST, assertIs<WireError>(recv()).code)
                    assertEquals(CloseReason.Codes.PROTOCOL_ERROR.code, awaitCloseCode())
                }
            }
        }
    }

    // ---------- C6: handshake ordering ----------
    // Mutation: drop the `handshook` guard on Send → a pre-Hello send is accepted → C6-negative reddens.
    @Test
    fun c6_handshakeOrdering() = runBlocking {
        platform().use { p ->
            // positive: hello then send works
            p.asAgent("po").use { c ->
                c.webSocket("${p.wsBaseUrl}/ws/hub") {
                    handshake(); sendFrame(WireSend("po-backend", "ordered")); assertIs<WireAck>(recv())
                }
            }
            // negative: a Send before Hello → WireError(PROTOCOL) + close PROTOCOL_ERROR
            p.asAgent("po").use { c ->
                c.webSocket("${p.wsBaseUrl}/ws/hub") {
                    sendFrame(WireSend("po-backend", "too early"))
                    assertEquals(WireErrorCode.PROTOCOL, assertIs<WireError>(recv()).code)
                    assertEquals(CloseReason.Codes.PROTOCOL_ERROR.code, awaitCloseCode())
                }
            }
        }
    }

    // ---------- C7: rate-limit (CYP-161) ----------
    // RC2: a DEDICATED platform → the per-agentId bucket starts full; no other case drains it.
    // Mutation: remove the rate-limiter from the wiring → the whole burst Acks + delivers → C7 reddens.
    @Test
    fun c7_rateLimit() = runBlocking {
        platform().use { p ->
            // body -> accepted? (Ack=true / RATE_LIMITED=false), recorded from the wire verdict per send, in order.
            val verdict = LinkedHashMap<String, Boolean>()
            p.asAgent("po").use { c ->
                c.webSocket("${p.wsBaseUrl}/ws/hub") {
                    handshake()
                    // A tight local burst past capacity (20): cumulative refill (5/s) stays < 1 token over the burst,
                    // so the tail is throttled deterministically. < floodCloseAfter (50) rejects → the connection stays open.
                    val bodies = (1..30).map { "C7-msg-$it" }
                    for (b in bodies) sendFrame(WireSend("po-backend", b))
                    // Replies arrive in send order, one per send: Ack (accepted) or WireError(RATE_LIMITED) (throttled).
                    for (b in bodies) {
                        when (val f = recv()) {
                            is WireAck -> verdict[b] = true
                            is WireError -> { assertEquals(WireErrorCode.RATE_LIMITED, f.code); verdict[b] = false }
                            else -> error("unexpected frame for '$b': $f")
                        }
                    }
                    assertTrue(verdict.values.any { it }, "pre-guard: in-capacity sends are accepted (delivery works)")
                    assertTrue(verdict.values.any { !it }, "a burst past capacity is throttled with RATE_LIMITED")
                }
            }
            // B reads history: EVERY accepted body is delivered; EVERY rate-limited body is absent (exact, non-vacuous).
            p.asAgent("backend").use { c ->
                c.webSocket("${p.wsBaseUrl}/ws/hub") {
                    handshake(); sendFrame(WireSubscribe(listOf("po-backend")))
                    val hist = drainSubscribeBodies()
                    for ((body, accepted) in verdict) {
                        if (accepted) assertTrue(hist.any { it.contains(body) }, "accepted send '$body' must be delivered")
                        else assertTrue(hist.none { it.contains(body) }, "rate-limited send '$body' must NOT be delivered")
                    }
                }
            }
        }
    }
}
