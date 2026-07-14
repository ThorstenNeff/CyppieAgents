package com.tneff.cyppieagents.relay

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.send

/**
 * CYP-506 (Epic CYP-427 Phase-2, activation) — the deployable **relay-server process**: an untrusted Ktor-WS relay
 * that pairs a hub with a client on an opaque rendezvous id and forwards opaque Noise frames (RR4). A **separate
 * deployable process** (its own `main`), co-located on the OakHost with the CP (Auftraggeber decision) and stood up
 * by `deploy`. It links **no** hub store / secret / crypto — it is a dumb pipe; the E2E Noise + RR3 auth run over it,
 * never on it.
 *
 * Wire (finalising the CYP-458 provisional scheme; the hub end goes live in CYP-509, the client end is Dev CYP-494):
 *  - `GET /relay` WebSocket, headers [RENDEZVOUS_HEADER] (opaque id) + [ROLE_HEADER] (`hub`|`client`). Missing/invalid
 *    → closed `VIOLATED_POLICY` (fail-closed).
 *  - one binary frame = one Noise message, forwarded verbatim (no length-prefix, LOCKED CYP-443).
 *  - `GET /health` → `ok`.
 */
fun Application.relayModule(
    relay: RendezvousRelay = RendezvousRelay(),
    /** CYP-549 — an optional wrapper applied to each joined [RelayPeer]. Prod = identity (no-op); a test injects a
     *  decorator (e.g. a frame counter) so the live-relay e2e exercises THIS real module rather than a hand-copied
     *  handler that could drift (the CYP-546 copy-drift class). Additive + default-identity → prod behaviour unchanged. */
    peerDecorator: (RelayPeer) -> RelayPeer = { it },
) {
    install(WebSockets)
    routing {
        get("/health") { call.respondText("ok") }

        webSocket("/relay") {
            val rzv = call.request.headers[RENDEZVOUS_HEADER]
            val role = when (call.request.headers[ROLE_HEADER]?.lowercase()) {
                "hub" -> RelayRole.HUB
                "client" -> RelayRole.CLIENT
                else -> null
            }
            if (rzv.isNullOrBlank() || role == null) {
                // Fail-closed: no opaque rendezvous id / no valid role → nothing to pair, refuse.
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "bad_rendezvous"))
                return@webSocket
            }
            relay.join(peerDecorator(WebSocketRelayPeer(this, rzv, role)))
        }
    }
}

const val RENDEZVOUS_HEADER = "X-Cyppie-Rendezvous"
const val ROLE_HEADER = "X-Cyppie-Role"

/**
 * CYP-506 — a live Ktor WS session presented as a [RelayPeer]. `receive()` yields the next **binary** frame's bytes
 * (skipping ping/pong), `null` on close; `send` writes exactly one binary frame; `close` is idempotent. The relay
 * never looks inside the bytes.
 */
class WebSocketRelayPeer(
    private val session: WebSocketSession,
    override val rendezvousId: String,
    override val role: RelayRole,
) : RelayPeer {
    override suspend fun receive(): ByteArray? {
        while (true) {
            val frame = session.incoming.receiveCatching().getOrNull() ?: return null
            if (frame is Frame.Binary) return frame.readBytes()
            // non-binary (ping/pong/close) is not tunnel data — skip; a channel close yields null above.
        }
    }

    override suspend fun send(frame: ByteArray) {
        session.send(Frame.Binary(true, frame))
    }

    override suspend fun close() {
        runCatching { session.close() }
    }
}

/**
 * Standalone entrypoint (run task `:server:relayRun`, or the [RelayServerKt][main] class in a deploy unit). Binds the
 * relay to `CYPPIE_RELAY_HOST`:`CYPPIE_RELAY_PORT` (defaults `0.0.0.0:8788` — the relay is the meeting point BOTH
 * ends dial into; the actual public exposure / reverse-proxy posture is **deploy-owned**, not decided here).
 */
fun main() {
    val port = System.getenv("CYPPIE_RELAY_PORT")?.toIntOrNull() ?: 8788
    val host = System.getenv("CYPPIE_RELAY_HOST") ?: "0.0.0.0"
    embeddedServer(Netty, port = port, host = host) { relayModule() }.start(wait = true)
}
