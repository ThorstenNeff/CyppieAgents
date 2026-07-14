package com.tneff.cyppieagents.transport

import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory

/**
 * CYP-536 (M2 Option A, WS1) — the source of the session's **rendezvous-id SET**. Per the ratified C4 (develop
 * `889e6919`): the CP mints a 128-bit secret epoch (never on the wire) and derives `id_i = base64url(SHA-256(hubId ‖
 * epoch ‖ i))` for `i ∈ 0..poolCap-1`; register/resolve return the SET. This seam yields that set on the hub side (the
 * epoch-N-set register is wired behind it — the coupled wire increment). `null`/empty ⇒ INERT / fail-closed (no live
 * relay, or the hub is not admitted+owned) — the manager then starts no responder, exactly like the single-connector
 * INERT gate. Kept a seam so the manager unit-tests without a real CP/relay.
 */
fun interface SessionRendezvousSource {
    suspend fun rendezvousIds(): List<String>?
}

/**
 * CYP-536 (M2 Option A, WS1) — the **N-concurrent responder manager**. It fans the single serial [NoiseRelayConnector]
 * (dial ONE cached id → NK-terminate → RR3-gate → bridge ONE tunnel → re-dial) into **N concurrent responders**, one
 * per rendezvous-id in the session's epoch-derived set ([source]). Each responder is an independent, persistent
 * [RelayConnector] built by [responderFor] for a FIXED id (a [NoiseRelayConnector] with a per-id dialer), so the hub is
 * present at the relay on **all N ids at once** — N client tunnels pair concurrently instead of serializing behind one
 * (the fix for F-M2-1, the mode-blind workspace's ~8 eager WS deadlocking a single tunnel).
 *
 * **Server-side per-operator tunnel CAP (WS6 C5 axis 2, the DoS floor).** The manager launches at most [poolCap]
 * responders — and because the CP derives exactly [poolCap] rendezvous-ids per session, at most [poolCap] distinct
 * tunnels can ever pair for one operator. The bounded id-set is thus the structural cap: a client's own pool limit is
 * *necessary but not sufficient* (a buggy/hostile client cannot exceed it — there is no `id_poolCap` to pair on). The
 * `.take(poolCap)` here is the explicit belt-and-suspenders guard should the source ever over-yield.
 *
 * **INERT** when [source] yields null/empty (no live relay / not owned) — parity with [NoiseRelayConnector]'s INERT
 * gate; the current server is unchanged until the Phase-2-Remote-GO wires a live source. Fail-closed throughout: a
 * single responder's failure is contained to its own persistent loop (each [NoiseRelayConnector] re-dials with backoff);
 * it never tears down the pool.
 */
class ConcurrentRelayResponderManager(
    private val source: SessionRendezvousSource,
    private val responderFor: (rendezvousId: String) -> RelayConnector,
    /** The server-side per-operator tunnel cap = the epoch-set size the CP derives (single-sourced with the CP). */
    private val poolCap: Int,
    @Suppress("unused") private val scope: CoroutineScope, // reserved: future manager-owned supervisory job
) : RelayConnector {
    private val log = LoggerFactory.getLogger("cyp536.responder.manager")
    private val responders = mutableListOf<RelayConnector>()

    override suspend fun start() {
        val ids = source.rendezvousIds()
        if (ids.isNullOrEmpty()) {
            log.info("CYP-536 N-responder manager INERT — no rendezvous set (relay INERT / hub not admitted+owned)")
            return
        }
        // CYP-536 WS6 axis 2 — never launch more than the per-operator cap, even if the source over-yields (DoS floor).
        val capped = ids.take(poolCap)
        if (ids.size > poolCap) {
            log.warn("CYP-536 rendezvous set size {} > poolCap {} — capping to the per-operator tunnel cap", ids.size, poolCap)
        }
        for (id in capped) {
            val responder = responderFor(id)
            responders += responder
            responder.start() // NoiseRelayConnector.start() launches its own persistent loop in its scope → non-blocking
        }
        log.info("CYP-536 N-responder manager started {} concurrent responders (cap {})", capped.size, poolCap)
    }

    override suspend fun stop() {
        responders.forEach { runCatching { it.stop() } } // best-effort teardown of every per-id responder
        responders.clear()
    }
}
