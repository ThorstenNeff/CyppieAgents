package com.tneff.cyppieagents.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CYP-506 (Epic CYP-427 Phase-2, activation) — the **untrusted rendezvous relay** core (RR4). It pairs exactly one
 * [RelayRole.HUB] with one [RelayRole.CLIENT] on a matching **opaque** rendezvous id and forwards opaque binary
 * frames **verbatim** between them (1 frame in = 1 frame out — message-preserving, no length-prefix, LOCKED CYP-443).
 *
 * It is a **dumb pipe** — exactly like the hub-side [com.tneff.cyppieagents.transport.LoopbackBridge] is dumb — so
 * the end-to-end Noise + RR3 auth stays entirely off the relay. Security posture (Reviewer-adversarial, teeth in
 * `Cyp506RelayServerTest`):
 *  - **never plaintext** — a frame is an opaque `ByteArray`, forwarded byte-identical; the relay never decodes/parses it.
 *  - **id opaque** — [RelayPeer.rendezvousId] is used ONLY as a `ConcurrentHashMap` key; never reversed to a `hubId`.
 *    (Its unlinkability/`epoch`-secrecy is a CP-side property, CYP-507 — the relay simply cannot invert it.)
 *  - **unpaired → no forward** — a lone peer forwards nothing; a 2nd-of-the-same-role or a 3rd peer on a full
 *    rendezvous is **rejected + closed**, never spliced into the pair.
 *  - **frame boundaries preserved** — each received frame is sent as exactly one frame (no split/merge/splice).
 *
 * Zero state beyond the live pairing table (opaque ids only). No auth AT the relay — it is untrusted by design.
 */
class RendezvousRelay {

    /** A rendezvous slot: the two role deferreds (completed by whoever arrives first for that role) + a single-owner
     *  latch (exactly ONE of the paired peers drives the bidirectional forwarding) + a `done` signal for the other. */
    private class Pairing {
        val hub = CompletableDeferred<RelayPeer>()
        val client = CompletableDeferred<RelayPeer>()
        val forwarder = AtomicBoolean(false)
        val done = CompletableDeferred<Unit>()
    }

    private val table = ConcurrentHashMap<String, Pairing>()

    /**
     * Join the relay with [peer]. Suspends for the lifetime of this peer's involvement: it registers under its
     * rendezvous id, waits for its partner, then (as the single forwarder) pumps frames both ways until either side
     * closes — or, if it is a duplicate role / third arrival, it is rejected and closed at once.
     *
     * Fail-closed throughout: a rejected or partner-less peer forwards nothing.
     */
    suspend fun join(peer: RelayPeer) {
        val pairing = table.compute(peer.rendezvousId) { _, existing -> existing ?: Pairing() }!!
        val self = if (peer.role == RelayRole.HUB) pairing.hub else pairing.client
        val other = if (peer.role == RelayRole.HUB) pairing.client else pairing.hub

        // Reject a 2nd peer of the SAME role (or a 3rd arrival) on this rendezvous — the slot is already taken.
        if (!self.complete(peer)) {
            peer.close()
            return
        }

        // Wait for the partner. If the pairing is torn down first, `other` completes exceptionally → fail-closed.
        val partner = try {
            other.await()
        } catch (_: Throwable) {
            peer.close()
            return
        }

        // Both present. Exactly ONE peer wins the forwarder latch and drives BOTH directions; the other parks on
        // `done`. This avoids double-pumping and the concurrent-arrival race.
        if (pairing.forwarder.compareAndSet(false, true)) {
            try {
                forward(peer, partner)
            } finally {
                table.remove(peer.rendezvousId, pairing)
                pairing.done.complete(Unit)
                peer.close()
                partner.close()
            }
        } else {
            pairing.done.await()
        }
    }

    /**
     * CYP-618 — pump [a]→[b] and [b]→[a] concurrently with an **ASYMMETRIC close** (close-ORDERING only; the relay
     * still forwards opaque frames verbatim, RR4 unchanged — no decode/parse/inspect, no new capability). The pair
     * tears down when the **HUB** side ends (its response is fully sent and it closed), NOT when the client side ends.
     *
     * This fixes the storm-root: the pre-CYP-618 SYMMETRIC close (`finally { a.close(); b.close() }` on EITHER pump)
     * tore the whole pair the instant the CLIENT closed, killing an in-flight hub→client response before the hub's
     * loopback down-pump could flush it → the client saw "server prematurely closed", re-dialed, and exhausted the
     * tunnel pool. Now a client-close only stops the client→hub direction; the hub→client leg stays alive so the hub
     * can flush its response. A bounded [GRACE_MS] backstops a hub that never closes after a client-close, so a
     * client-close can NEVER leak the pairing (no unbounded hold, no new DoS surface).
     *
     * **Efficacy bound:** this recovers the response only when the client's READ side is still open after it stopped
     * sending (a graceful drain). A client that HARD-closes its connection (WS full close) is gone and gets nothing —
     * that residual is the client-pool teardown-under-pressure, a separate client-side concern.
     */
    private suspend fun forward(a: RelayPeer, b: RelayPeer) = coroutineScope {
        val hub = if (a.role == RelayRole.HUB) a else b
        val client = if (a.role == RelayRole.CLIENT) a else b
        val hubToClient = launch { runCatching { pump(hub, client) } } // hub→client: its END drives the teardown
        val clientToHub = launch { runCatching { pump(client, hub) } } // client→hub: its end does NOT tear the pair
        // If the client stops first, give the hub a bounded grace to flush its response, then force the teardown.
        val graceBackstop = launch {
            clientToHub.join()
            delay(GRACE_MS)
            hubToClient.cancel() // grace expired after a client-close → bring the hub→client leg down (no pairing leak)
        }
        try {
            hubToClient.join() // tear when the hub finished (response sent + closed) OR the grace-backstop cancelled it
        } finally {
            hub.close(); client.close()
            clientToHub.cancel(); graceBackstop.cancel()
        }
    }

    /** One-for-one verbatim forwarding: each inbound frame from [from] is sent as exactly one frame to [to]. The
     *  relay never inspects the bytes (RR4 — opaque). */
    private suspend fun pump(from: RelayPeer, to: RelayPeer) {
        while (true) {
            val frame = from.receive() ?: break
            to.send(frame)
        }
    }

    /** Live pairing count (test/observability) — never exposes ids or bytes. */
    fun activePairings(): Int = table.size

    private companion object {
        /** CYP-618 — bounded grace for the hub to flush its response after a client-close, before the pair is torn.
         *  Small (an HTTP response drains fast) yet bounded, so a client-close can never leak the pairing. */
        const val GRACE_MS = 2_000L
    }
}
