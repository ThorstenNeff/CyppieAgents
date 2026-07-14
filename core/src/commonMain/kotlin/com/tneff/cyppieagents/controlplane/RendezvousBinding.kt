package com.tneff.cyppieagents.controlplane

import kotlinx.serialization.Serializable

/**
 * CYP-507 (Epic CYP-427 Phase-2, activation) — the **client↔CP wire contract** for the relay rendezvous. The CP
 * returns this from `GET /api/cp/rendezvous/{hubId}` (resolve, consumed by Dev CYP-494 `RelayDialer`) and from the
 * hub-side register: the **opaque** rendezvous id both ends dial the relay under, plus the relay URL.
 *
 * [rendezvousId] = `base64url(SHA-256(hubId ‖ epoch))` where `epoch` is a CP-secret per-registration 128-bit value
 * that **never leaves the CP** — the relay (and the requester) see only this derived id, never the `hubId` (RR4). A
 * new registration rotates the epoch → a fresh id (unlinkable across registrations). Consumed by the desktop client
 * (Kotlin) and regenerated as a TypeScript type for the web (Team-2). INERT until the Phase-2-Remote-GO.
 */
/**
 * CYP-536 (M2 Option A, C4 ratified develop `20db04ad`) — [rendezvousIds] is the **epoch-derived N-set** the client
 * dials for N concurrent tunnels: `[id_0 .. id_{cap-1}]`, all derived CP-side from the same secret epoch (the epoch
 * still **never leaves the CP** — the client does NOT re-derive, it dials the returned ids). **Additive + defaulted**
 * (`emptyList()`) → the single-tunnel wire is byte-unchanged; a legacy consumer reads [rendezvousId] and ignores this.
 * `rendezvousIds.first() == rendezvousId` when populated (element 0 is the legacy base id). The hub runs one responder
 * per id (WS1); the client dials as many as its pool needs (WS2); `cap` (= the set size) is the server-side per-operator
 * tunnel CAP and MUST be ≥ the client pool cap (C2).
 */
@Serializable
data class RendezvousBinding(
    val rendezvousId: String,
    val relayUrl: String,
    val rendezvousIds: List<String> = emptyList(),
)

/**
 * CYP-507 — the resolve/register **response** (client CYP-494 ↔ CP). Exactly one of [binding] (found) / [failure] is
 * set. Business outcomes are a **200 with a typed body** (the mint/ModeRoutes idiom); only structural failures are
 * HTTP errors (401/403 auth). So Dev parses ONE shape and renders the correct cause. [relayUrl] rides inside
 * [binding] — the CP is the single source (its co-located `CYPPIE_REMOTE_RELAY_URL`); the browser client never needs
 * its own relay config.
 */
@Serializable
data class RendezvousResolveResponse(
    val binding: RendezvousBinding? = null,
    val failure: RendezvousFailure? = null,
)

/**
 * CYP-507 — the operator-facing resolve/register failures Dev must tell apart. The client receives the **opaque
 * `rendezvousId`** (never the CP-secret epoch): re-deriving it from a CP-supplied epoch would add no trust (a bad CP
 * supplies a bad epoch just as easily → GIGO); the real integrity is downstream — the TOFU-pinned hub `dhPubKey`
 * (CYP-478/495) fails a wrong-rendezvous Noise handshake closed. The id is a routing label, not a trust anchor.
 */
@Serializable
enum class RendezvousFailure {
    /** The hub has no current rendezvous — it never registered / is offline. The client waits/retries. */
    NOT_REGISTERED,

    /** No live relay is configured at the CP (the Phase-2 activation gate is off) — INERT. */
    RELAY_UNAVAILABLE,
}
