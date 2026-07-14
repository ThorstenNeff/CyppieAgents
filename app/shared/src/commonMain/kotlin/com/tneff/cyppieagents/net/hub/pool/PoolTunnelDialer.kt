package com.tneff.cyppieagents.net.hub.pool

import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel

/**
 * CYP-537 (M2 Option A, WS2) — the pool's two establishment steps, shaped by contract **C4** (PO ruling
 * develop `889e6919`): the rendezvous-ids are **CP-epoch-derived + opaque** (`id_i = base64url(SHA-256(hubId ‖
 * epoch ‖ i))`, the epoch is a CP secret never on the wire) — **not** client-invented. The client **resolves the
 * N-set once** ([rendezvousSet]) and then dials each opaque id from it ([dial]). "Client-driven" (C4) = the pool
 * owns **count + lifecycle** (how many of the set it opens + teardown); the ids stay CP-derived + opaque.
 *
 *  1. [rendezvousSet] — resolve the session's CP N-set of ≤[TUNNEL_POOL_CAP] opaque ids (once); `null`
 *     fail-closed (CP/relay unreachable ⇒ the pool yields no tunnels ⇒ INERT, never a fabricated set).
 *  2. [dial] — establish ONE **distinct, live, PoP-authenticated** [NoiseTunnel] over a given opaque id from the
 *     set; `null` **fail-closed** on ANY failure (dial / trust-changed / handshake / auth-reject) — the pool then
 *     yields `null` and the transport RSTs that connection (never a plaintext/local fallback, C2 invariant).
 *
 * The real impl is [NoisePoolTunnelDialer] (composes the same RelayDialer/HubTrust/ClientNoiseTransport/
 * OperatorAuthenticator seams the [com.tneff.cyppieagents.net.hub.remote.RemoteHubSession] uses, so pool tunnels
 * ride the trust the session already pinned + the device it already enrolled). Tests inject a fake.
 */
interface PoolTunnelDialer {
    /** The CP-resolved N-set of opaque rendezvous-ids for this session (C4), or `null` fail-closed. Resolved ONCE. */
    suspend fun rendezvousSet(): List<String>?

    /** Establish one authenticated tunnel over the opaque [rendezvousId] from the set, or `null` fail-closed. */
    suspend fun dial(rendezvousId: String): NoiseTunnel?
}

/**
 * CYP-535 (H7) — a tunnel the byte-pump can flag when its per-tunnel in-flight **send-window** fills (≤8 frames,
 * `docs/design/H7-backpressure-bound-design.md`). [PooledTunnelSource] hands out tunnels that implement this so a
 * full window surfaces as [TunnelState.BACKPRESSURED] in the C3 [TunnelPoolState] (a healthy-but-slow tunnel — never
 * an alarm, never a close). The pump toggles it via `tunnel as? BackpressureSignal`; a plain (non-pooled) tunnel
 * simply isn't one, so the signal is a no-op — the backpressure block itself is unconditional (memory stays bounded).
 */
interface BackpressureSignal {
    /** [active] = the send-window is currently full (reader suspended); `false` = it drained (credit available). */
    fun onBackpressured(active: Boolean)
}
