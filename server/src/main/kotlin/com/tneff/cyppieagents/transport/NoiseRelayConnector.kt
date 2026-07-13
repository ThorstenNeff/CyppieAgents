package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.controlplane.AdmissionRetry
import com.tneff.cyppieagents.relay.RENDEZVOUS_HEADER
import com.tneff.cyppieagents.relay.ROLE_HEADER
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * CYP-458 (S2) — the **outbound-only** relay dial seam (RR5): opens a connection **TO** the relay and returns the L0
 * frame channel. It NEVER binds a listener for the tunnel — an inbound/wildcard listener would be exactly the
 * exposition RR5 forbids. Tests inject a fake dialer; the production impl is [WebSocketRelayDialer].
 */
interface RelayDialer {
    suspend fun dial(relayUrl: String): ServerRelayChannel
}

/**
 * The production [RelayDialer]: dials the relay WebSocket **outbound** through a Ktor [HttpClient] (WebSockets
 * plugin) and registers under an **opaque** [rendezvousId] (RR4 — the relay sees only the id + ciphertext +
 * sizes/timing, never the hub identity or any payload). The exact registration scheme is **provisional** (finalised
 * with the relay server, which does not exist yet); it is carried here as an opaque header.
 */
class WebSocketRelayDialer(
    private val client: HttpClient,
    /** CYP-521: the rendezvous id is obtained FRESH at dial-time from the CP register (the epoch-derived id), NOT a
     *  static env value — a static id can never match `LiveRelayRendezvous.register`'s per-registration epoch id, so
     *  the relay would never pair. `null` (registration failed / not owned / INERT) → fail-closed, no dial. */
    private val rendezvousId: suspend () -> String?,
) : RelayDialer {
    override suspend fun dial(relayUrl: String): ServerRelayChannel {
        // CYP-521: register with the CP → the CURRENT epoch id, then CYP-509: dial the relay as role=hub under it.
        val id = rendezvousId() ?: error("CYP-521: hub rendezvous registration failed — cannot dial the relay")
        val session = client.webSocketSession(relayUrl) {
            header(RENDEZVOUS_HEADER, id)
            header(ROLE_HEADER, HUB_ROLE)
        }
        return RelayChannelOverWebSocket(session)
    }

    private companion object {
        /** The relay parses `X-Cyppie-Role` as `hub`|`client`; the hub end is always `hub`. */
        const val HUB_ROLE = "hub"
    }
}

/**
 * CYP-458 (S2) — the **outbound-only reverse-tunnel** connector (impl of [RelayConnector]). On [start] — **only** when
 * the [RemoteTransportConfig] is enabled (INERT / local-only otherwise, parity with the `CYPPIE_MASTER_KEY`-gated CP
 * wiring) — it DIALS the relay via the [RelayDialer], runs the [ServerNoiseTerminator] (NK responder on the hub
 * `dhKey` static, CYP-457) over the L0 channel, and hands the terminated L2 tunnel to [tunnelHandler] — in production
 * `LoopbackBridge.bridge` (present L2 to the EXISTING Ktor routes over 127.0.0.1).
 *
 * **AC1 (RR5):** it only ever DIALS — there is no listener for the tunnel. **§5:** one L2 per tunnel (this MVP dials
 * one; multi-tunnel accept lands with the relay protocol). **Fail-closed:** a handshake failure closes the L0 channel.
 * INERT until CYP-459 boot-wiring flips [RemoteTransportConfig.enabled] on an explicit Phase-2-Remote-GO.
 */
class NoiseRelayConnector(
    private val config: RemoteTransportConfig,
    private val dialer: RelayDialer,
    private val terminator: ServerNoiseTerminator,
    private val tunnelHandler: suspend (ServerNoiseTunnel) -> Unit,
    private val scope: CoroutineScope,
    /** CYP-526 — backoff before a re-dial after a FAILURE (the loop is UNBOUNDED — a persistent responder). Reuses the
     *  CYP-524 [AdmissionRetry] exponential curve (capped). Injectable for tests (fast/deterministic). */
    private val backoffMs: (attempt: Int) -> Long = { a -> AdmissionRetry().delayForAttempt(a) },
    /** CYP-528 — a MIN-INTERVAL floor on the **clean tunnel-end** re-dial path only. A real session (held ≥ floor)
     *  still re-dials immediately (elapsed ≥ floor → 0 wait); only a near-instant tunnel-end (a churn: rapid
     *  connect/disconnect, or terminate/bridge returning without a real session) is throttled to `floor - elapsed`
     *  so the loop can't `delay(0)` tight-spin. The FAILURE path is unchanged (already capped by [backoffMs]). */
    private val cleanEndFloorMs: Long = 500L,
    /** Injectable monotonic clock (ms) for the clean-end elapsed measurement; tests drive it deterministically. */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** Injectable sleeper so tests observe the computed re-dial delay without real waiting; prod = [delay]. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : RelayConnector {
    private val log = LoggerFactory.getLogger("cyp459.relay.connector")
    private var job: Job? = null

    override suspend fun start() {
        val url = config.relayUrl
        if (!config.enabled || url == null) {
            // CYP-526: log the INERT reason so "no dial line" is never ambiguous between INERT vs a silent throw.
            log.info("CYP-459 relay connector INERT (enabled={}, relayUrl set={}) — no outbound dial", config.enabled, url != null)
            return
        }
        // CYP-526: a PERSISTENT responder. Dial → terminate → bridge, then on tunnel-end OR any failure re-dial with
        // backoff — NEVER a one-shot (the pre-CYP-526 bug: a single transient dial/handshake throw killed the coroutine
        // and the hub was never present at the relay again). Every failure is caught + logged, so the CYP-524-class
        // throw between a successful register and an established relay WS is now VISIBLE + retried, not swallowed.
        job = scope.launch {
            var attempt = 0
            while (isActive) {
                val dialStart = nowMs() // CYP-528: measure how long this dial→bridge cycle held (for the clean-end floor)
                try {
                    log.info("CYP-526 dialing relay as role=hub")
                    val relay = dialer.dial(url) // outbound-only (WS to the relay under the CACHED rendezvous id)
                    attempt = 0 // a live dial resets the backoff
                    val tunnel = try {
                        terminator.terminate(relay) // NK responder handshake over L0
                    } catch (e: Exception) {
                        relay.close() // fail-closed: a failed handshake tears the L0 channel down
                        throw e
                    }
                    log.info("CYP-526 relay responder established — bridging tunnel")
                    tunnelHandler(tunnel) // → LoopbackBridge.bridge; returns when the tunnel closes
                    log.info("CYP-526 relay tunnel ended — re-dialing to remain a persistent responder")
                } catch (c: CancellationException) {
                    throw c // stop() cancelled us — exit cleanly, no reconnect
                } catch (e: Exception) {
                    attempt++
                    log.warn("CYP-526 relay dial/handshake failed (attempt {}): {}", attempt, e.message)
                }
                val wait = if (attempt == 0) {
                    // CYP-528 clean-end floor: re-dial immediately when this cycle held ≥ the floor (a real session);
                    // otherwise throttle to the remainder so a fast/instant tunnel-end can't `delay(0)` tight-loop.
                    (cleanEndFloorMs - (nowMs() - dialStart)).coerceAtLeast(0L)
                } else {
                    backoffMs(attempt) // failure path unchanged — already capped
                }
                sleep(wait)
            }
        }
    }

    override suspend fun stop() {
        job?.cancelAndJoin()
        job = null
    }
}
