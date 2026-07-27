package com.tneff.cyppieagents.agentview

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket as serverWebSocket

/**
 * CYP-819 (the behavioral regression tooth, Compose parity to web-ts CYP-815) — a **1008 (VIOLATED_POLICY)
 * auth-revoke on EACH of the four read-only status feeds** (`/ws/lifecycle`, `/ws/token-usage`, `/ws/busy-state`,
 * `/ws/terminal-state`) is **terminal**: the feed's `events()` flow ENDS (no dead-token reconnect hammer, the
 * CYP-289 loop class) and fires `onRevoked` (which drives the covering banner + the per-window indicator demotion).
 * Before the fix these four were the only live sources that `.reconnecting()`-looped forever on any close, freezing
 * the last state as if it were current (the safe-but-silent class).
 *
 * The four are exercised INDEPENDENTLY (a fix wiring only one would leave three silent). They share the
 * [statusFeed]/[terminalOnRevoke] contract, so the mutation that proves this tooth — reverting the
 * `if (isAccessRevoked(closeCode)) send(StatusFeedSignal.Revoked)` guard in [statusFeed] (or the
 * `takeWhile { it !is Revoked }` in [terminalOnRevoke]) — reddens ALL four at once: `onRevoked` never fires AND
 * `events()` never terminates (the reconnect loop returns), so `withTimeout` below throws → each test RED.
 *
 * NOT render-based (embedded WS server + a plain flow collect) → no headless Compose-UI hang. The visible
 * covering banner + indicator demotion are proven separately in the render teeth; UIUX+Tester run a live mid-session
 * 1008 on top of this.
 */
class Cyp819StatusFeedRevokeTest {

    /** Stand up a WS server that closes [route] with **1008** immediately; build the source over it and assert its
     *  `events()` TERMINATES (no hammer) AND `onRevoked` fired. `withTimeout` is the anti-hammer assertion: if the
     *  feed re-dialed the dead token forever (the bug / the mutation), `toList()` would never return. */
    private fun assertRevokeIsTerminal(route: String, makeEvents: (HttpClient, String, () -> Unit) -> Flow<*>) = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing {
                serverWebSocket(route) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "revoked"))
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                var revoked = false
                val events = makeEvents(client, "ws://127.0.0.1:$port") { revoked = true }
                // Terminates iff the 1008 is terminal (takeWhile cuts the reconnect); would hang forever otherwise.
                withTimeout(10_000) { events.toList() }
                assertTrue(revoked, "$route: a 1008 must fire onRevoked (the covering-revoke signal)")
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }

    @Test
    fun lifecycleFeed_1008_isTerminal_andSignalsRevoke() = assertRevokeIsTerminal("/ws/lifecycle") { c, ws, onR ->
        AgentLifecycleLiveSource(c, httpBaseUrl = ws, wsBaseUrl = ws, token = "bad", onRevoked = onR).events()
    }

    @Test
    fun tokenUsageFeed_1008_isTerminal_andSignalsRevoke() = assertRevokeIsTerminal("/ws/token-usage") { c, ws, onR ->
        TokenUsageLiveSource(c, wsBaseUrl = ws, token = "bad", onRevoked = onR).events()
    }

    @Test
    fun busyStateFeed_1008_isTerminal_andSignalsRevoke() = assertRevokeIsTerminal("/ws/busy-state") { c, ws, onR ->
        BusyStateLiveSource(c, wsBaseUrl = ws, token = "bad", onRevoked = onR).events()
    }

    @Test
    fun terminalControlFeed_1008_isTerminal_andSignalsRevoke() = assertRevokeIsTerminal("/ws/terminal-state") { c, ws, onR ->
        TerminalControlLiveSource(c, wsBaseUrl = ws, token = "bad", onRevoked = onR).events()
    }

    /**
     * G1 (Reviewer re-review criterion) — the **NON-revoked side, positively toothed**: the 1008-terminal branch
     * must DISCRIMINATE, not latch on any close. A **NON-1008** server close (here `NORMAL`/1000, a transient drop)
     * must NOT fire `onRevoked` AND the feed must **re-subscribe** — proven POSITIVELY by a **second server
     * connection**, not merely by "did not terminate". Shared `statusFeed`/`terminalOnRevoke`, so one feed proves
     * the discrimination for all four.
     *
     * Non-vacuity: mutation `isAccessRevoked(_) → true` (over-eager, broader than `== VIOLATED_POLICY`) makes the
     * NORMAL close emit `Revoked` → `onRevoked` fires + `takeWhile` terminates the feed → NO reconnect → the
     * `connects == 2` wait times out AND `revoked` is true → RED. So a transient blip can never sticky-latch a revoke.
     */
    @Test
    fun nonRevokedClose_doesNotSignalRevoke_andReconnects() = runBlocking {
        val connects = AtomicInteger(0)
        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing {
                serverWebSocket("/ws/busy-state") {
                    val n = connects.incrementAndGet()
                    if (n == 1) close(CloseReason(CloseReason.Codes.NORMAL, "bye")) // NON-1008 transient drop
                    else awaitCancellation() // stay open on the reconnect → the count settles at 2 (positive proof)
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                var revoked = false
                val src = BusyStateLiveSource(client, wsBaseUrl = "ws://127.0.0.1:$port", token = "tok", onRevoked = { revoked = true })
                val job = launch { src.events().collect {} } // drive the reconnect loop
                withTimeout(10_000) { while (connects.get() < 2) delay(20) } // POSITIVE reconnect proof (2nd connection)
                assertEquals(2, connects.get(), "a NON-1008 close is transient → the feed must RE-SUBSCRIBE")
                assertFalse(revoked, "a NON-1008 close must NOT latch a revoke (an over-eager isAccessRevoked = false-positive)")
                job.cancel()
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }
}
