package com.tneff.cyppieagents.controlplane

import java.security.MessageDigest
import java.util.Base64

/**
 * CYP-501 (activation, INERT) — the CP-side **rendezvous registry/resolver** for the untrusted relay (RR4 opaque, RR5
 * outbound-only). The relay never learns `hubId`: both ends connect under an **opaque** rendezvous id derived from the
 * `hubId` + a CP-issued per-registration epoch; the CP is the sole `hubId`↔rendezvous resolver. The hub [register]s
 * (outbound, `WebSocketRelayDialer`); the client [resolve]s by `hubId` (CYP-494 `RelayDialer`). **INERT** — no live
 * relay until the Phase-2-Remote-GO. The relay SERVER (a process) is NOT built here — its operation is Auftraggeber-GO.
 */
interface RelayRendezvous {
    /** Hub side (outbound): publish this hub's current rendezvous; null when no live relay is configured. */
    fun register(hubId: String): RendezvousBinding?

    /** Client side (by `hubId`): resolve this hub's current rendezvous; null when there is none. */
    fun resolve(hubId: String): RendezvousBinding?

    /** Whether a live relay is configured (the Phase-2 activation gate is on). Lets the endpoint tell
     *  `RELAY_UNAVAILABLE` (INERT) apart from `NOT_REGISTERED` (live, but this hub has no rendezvous). */
    fun isLive(): Boolean

    companion object {
        /**
         * CYP-536 (M2 Option A, C4 ratified develop `889e6919`) — the **single-sourced** default per-session tunnel
         * pool cap = the number of epoch-derived rendezvous-ids the CP mints = the **server-side per-operator tunnel
         * CAP** (WS6 axis 2, the DoS floor). One const feeds BOTH the CP set-derivation ([rendezvousIdSet]) and the
         * [com.tneff.cyppieagents.transport.ConcurrentRelayResponderManager] responder cap, so the two can never drift
         * (the "single-source derived values" rule). Sized to the mode-blind workspace's ~8 eager WS + headroom.
         */
        const val DEFAULT_TUNNEL_POOL_CAP: Int = 16

        /** RR4 opaque id: `base64url(SHA-256(hubId ‖ epoch))` — the relay sees only this, never `hubId`. The
         *  [rendezvousEpoch] is **per-registration**: a new registration rotates the id (unlinkable across
         *  REGISTRATIONS). Within one registration the id is stable → the relay can correlate that hub's sessions =
         *  an accepted RR4 metadata residual (relay already sees pairing/timing/sizes), not per-session unlinkability. */
        fun rendezvousId(hubId: String, rendezvousEpoch: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(hubId.encodeToByteArray())
            md.update(rendezvousEpoch)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(md.digest())
        }

        /**
         * CYP-536 — the **indexed** opaque id for tunnel `index` of an N-tunnel session:
         * `base64url(SHA-256(hubId ‖ epoch ‖ BE32(index)))`. Distinct per index (a fresh SHA-256 preimage) → N distinct
         * relay pairings (relay unchanged, still 1↔1 per opaque id). Derived **only CP-side** — both the hub register
         * and the client resolve receive the derived id from the CP (the epoch is a CP-secret, never on the wire), so
         * the exact index encoding is CP-internal and the client never re-derives. The 4-byte big-endian [index] tail
         * is unambiguous (fixed width) → no boundary collision with the epoch.
         */
        fun rendezvousId(hubId: String, rendezvousEpoch: ByteArray, index: Int): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(hubId.encodeToByteArray())
            md.update(rendezvousEpoch)
            md.update(byteArrayOf((index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte()))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(md.digest())
        }

        /**
         * CYP-536 — the full epoch-derived rendezvous **set** for one session (the C4 N-set). The CP returns this on
         * register/resolve; the hub runs one responder per id, the client dials as many as its pool needs. `cap` is
         * bounded ([DEFAULT_TUNNEL_POOL_CAP]) = the per-operator tunnel CAP.
         *
         * **Element 0 is the legacy UNINDEXED base id** (`rendezvousId(hubId, epoch)`); elements 1..cap-1 are the
         * indexed ids. So `set.first()` equals the single-tunnel `RendezvousBinding.rendezvousId` **byte-for-byte** —
         * a legacy single-tunnel client (reading only `rendezvousId`) and the hub still pair on element 0 unchanged
         * (**zero behaviour change to the merged single-tunnel path**), while an N-tunnel client reads the whole set.
         * All `cap` ids are distinct (the base differs from every indexed id; the indexed ids differ per index).
         */
        fun rendezvousIdSet(hubId: String, rendezvousEpoch: ByteArray, cap: Int): List<String> =
            listOf(rendezvousId(hubId, rendezvousEpoch)) + (1 until cap).map { i -> rendezvousId(hubId, rendezvousEpoch, i) }
    }
}

// [RendezvousBinding] is the :core client↔CP wire contract (CYP-507) — same package, single-sourced there.

/** INERT default: no live rendezvous (register/resolve yield null). The current server is unchanged. */
object InertRelayRendezvous : RelayRendezvous {
    override fun register(hubId: String): RendezvousBinding? = null
    override fun resolve(hubId: String): RendezvousBinding? = null
    override fun isLive(): Boolean = false
}
