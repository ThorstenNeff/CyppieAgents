package com.tneff.cyppieagents.net.hub.relay

/**
 * CYP-494 (Client-Remote-Runway #1) — resolves a `hubId` to its current relay rendezvous via the Control-Plane
 * (contract ①, CYP-501 §2 / CYP-507). An **injected seam**: the live HTTP impl (mapping the `:core`
 * `RendezvousResolveResponse`, `GET /api/cp/rendezvous/{hubId}`, operator-gated) lands with the CYP-507 `:core`
 * DTO; the dialer + tests build against this seam now, so nothing is guessed against an unmerged wire type.
 *
 * The rendezvous id is a **routing label, not a trust anchor** (Backend decision): the client receives the
 * finished opaque id — integrity is the downstream TOFU pin (CYP-478) + Noise_NK fail-closed, not any client-side
 * re-derivation. A [RendezvousResolution.Failed] is a typed business outcome (the CP returns 200 + a typed body);
 * a transport error (CP unreachable) is thrown by the impl (→ RelayUnreachable), it is NOT a resolution value.
 */
fun interface RendezvousResolver {
    suspend fun resolve(hubId: String): RendezvousResolution
}

sealed interface RendezvousResolution {
    /**
     * The CP paired the hub to a relay: dial [relayUrl], joining the opaque [rendezvousId] as the client role.
     *
     * CYP-537 (M2-A / C4): [rendezvousIds] is the **epoch-derived N-set** (`:core RendezvousBinding.rendezvousIds`,
     * CYP-536) — the CP-derived opaque ids the pool dials for N concurrent tunnels. `rendezvousIds.first() ==
     * rendezvousId` when populated (element 0 = the legacy base id the SESSION's control tunnel uses); the pool
     * dials the REST (`drop(1)`) so it never collides with the session's 1↔1 relay pairing. Additive + defaulted
     * (`emptyList()`) → the single-tunnel legacy resolve is byte-unchanged.
     */
    data class Bound(
        val rendezvousId: String,
        val relayUrl: String,
        val rendezvousIds: List<String> = emptyList(),
    ) : RendezvousResolution

    /** A typed unavailability (mirror of the `:core` `RendezvousFailure`) → a distinct operator-facing cause. */
    data class Failed(val cause: RendezvousUnavailable) : RendezvousResolution
}

/** The two typed resolve failures (CYP-501 §2 / CYP-507): the hub isn't registered/online, or no relay is up. */
enum class RendezvousUnavailable { NOT_REGISTERED, RELAY_UNAVAILABLE }
