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
         * (the "single-source derived values" rule).
         *
         * **CYP-611 (dogfood 2026-07-15, 16→24 — Auftraggeber-AUTHORIZED DoS-envelope change):** the mode-blind
         * client pool opens one tunnel per loopback connection (WS long-lived + 1 dedicated REST tunnel + 1 control),
         * so at the 7-agent default the 15 data-ids (`cap-1`, after the control id at element 0) were filled EXACTLY
         * (7 agent-WS + 7 singleton-WS + 1 REST) — an 8th agent exhausted the pool. Raising the cap to 24 gave
         * ~23 data-ids ⟹ headroom to ~16 agents. That was a deliberate LOOSENING of the per-operator DoS floor, so it
         * required the Auftraggeber's out-of-band GO (NOT an autonomous change).
         *
         * **CYP-611 REVERT (2026-07-27, 24→16 — a TIGHTENING back toward the original DoS floor):** the CYP-840
         * status-mux consolidates the 4 singleton status feeds (lifecycle/token-usage/busy-state/terminal-state) into
         * ONE `/ws/status`, so the client's WS working set drops (6 globals → 3) and the pre-mux 8th-agent exhaustion
         * foot-gun the 24-bump compensated for is relieved (the sizing analysis: the post-mux ~11-WS working set fits
         * the cap-16 WS gate of 13 with headroom). Tightening the DoS floor is the SAFE direction (it strengthens, not
         * weakens, the per-operator envelope) — this is the follow-through the [TunnelPoolState] partition KDoc
         * anticipated ("consolidate the singletons → relieve pool pressure without a cap bump"), run in reverse.
         *
         * The **relay imposes no per-session ceiling** ([com.tneff.cyppieagents.relay.RendezvousRelay] pairs 1↔1 per
         * opaque id, unbounded), so the cap is purely the client-pool DoS floor. **Lockstep obligation (C2):** the
         * server cap MUST stay ≥ the client `TUNNEL_POOL_CAP` at ALL times, and the two consts are separate (client in
         * `:app:shared`, not a shared `:core` const yet) → the revert MUST land **client-first** (client 24→16 in
         * develop, THEN this server const), else the client (still self-limiting to 24) is handed only 16 rendezvous-ids
         * and its cap-24 partition math under-provisions its WS lane. Change BOTH; order matters.
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
