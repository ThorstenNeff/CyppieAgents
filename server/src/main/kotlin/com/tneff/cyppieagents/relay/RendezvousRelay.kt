package com.tneff.cyppieagents.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
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

    /** Pump [a]→[b] and [b]→[a] concurrently. When EITHER direction hits EOF, both peers are closed, so the other
     *  direction's `receive()` returns `null` and the whole pair tears down (no half-open session). */
    private suspend fun forward(a: RelayPeer, b: RelayPeer) = coroutineScope {
        launch { try { pump(a, b) } finally { a.close(); b.close() } }
        launch { try { pump(b, a) } finally { a.close(); b.close() } }
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
}
