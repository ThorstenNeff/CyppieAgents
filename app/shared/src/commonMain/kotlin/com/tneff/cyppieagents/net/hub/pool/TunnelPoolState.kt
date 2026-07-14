package com.tneff.cyppieagents.net.hub.pool

/**
 * CYP-537 (M2 Option A, WS2) — the N-tunnel pool's **per-tunnel observability**, frozen by the coupling contract
 * **C3** (`docs/design/M2-A-ntunnel-workstream-split-and-contract.md`). The pooling `TunnelSource`
 * ([PooledTunnelSource]) OWNS the emitter; WS5 (per-tunnel status UX chrome) renders it and WS4 (the N-tunnel E2E
 * harness) asserts on it. The **enum + shapes below are the frozen contract** — Team-2 builds against them; any
 * shape change is a PO-ratified doc revision.
 */

/**
 * The lifecycle of ONE pooled tunnel (C3). [DIALING] = the dial+handshake+PoP is in flight; [UP] = live +
 * authenticated, carrying (or ready to carry) a workspace connection; [BACKPRESSURED] = live but its H7 in-flight
 * send-window is full (a healthy-but-slow tunnel — NOT down, never an alarm); [DOWN] = the tunnel closed (its
 * connection ended, or a relay drop) and its pool slot is freed. A dial that fails-closed never reaches [UP] — it
 * leaves the pool (the transport RSTs that connection, C2), it does not linger as a phantom entry.
 */
enum class TunnelState { DIALING, UP, BACKPRESSURED, DOWN }

/** One tunnel's status: its client-allocated [rendezvousId] (C4), its [state], and when it entered that state. */
data class TunnelStatus(
    val rendezvousId: String,
    val state: TunnelState,
    val sinceTs: Long,
)

/**
 * The pool-wide roll-up (C3). [active] = tunnels currently [TunnelState.UP]/[TunnelState.BACKPRESSURED] (not DIALING,
 * not DOWN); [cap] = [TUNNEL_POOL_CAP] (the aggregate bound — H7 §5: per-tunnel windows × cap bounds total memory);
 * [anyBackpressured] = at least one live tunnel's send-window is full (a coarse "workspace is throttling" hint).
 */
data class TunnelPoolAggregate(
    val active: Int,
    val cap: Int,
    val anyBackpressured: Boolean,
)

/** The whole pool state stream (C3): the list of per-tunnel [tunnels] + the [aggregate] roll-up. */
data class TunnelPoolState(
    val tunnels: List<TunnelStatus>,
    val aggregate: TunnelPoolAggregate,
) {
    companion object {
        /** The initial (no tunnels dialed yet) state at [cap]. */
        fun empty(cap: Int): TunnelPoolState =
            TunnelPoolState(tunnels = emptyList(), aggregate = TunnelPoolAggregate(active = 0, cap = cap, anyBackpressured = false))
    }
}

/**
 * CYP-537 (C2) — the N-tunnel pool cap. This is the aggregate bound the whole A-design leans on:
 *  - **F-M2-1 fix:** the CONNECTED mode-blind workspace (CYP-411) opens ~8 eager persistent WS on mount
 *    (Capacity/Lifecycle/Comm/Events/ACL/TokenUsage/Busy/TerminalControl) **plus** per-agent WS. One tunnel = one
 *    duplex stream (no mux) ⇒ single-flight deadlocks on the first persistent WS. The pool needs a slot per
 *    concurrent logical connection, so the cap MUST exceed the eager-8 + a working set of agents.
 *  - **Single-sourced to the SERVER cap (WS1↔WS2 convergence):** Backend's `DEFAULT_TUNNEL_POOL_CAP = 16` is the
 *    **authoritative structural ceiling** — the CP derives **exactly** that many opaque rendezvous-ids (C4), so at
 *    most 16 tunnels can ever pair (the per-operator DoS floor). The **effective** pool is therefore
 *    `min(TUNNEL_POOL_CAP, rendezvousIds.size)`: the client is structurally bound by the resolved set-size — it can
 *    only dial ids the CP handed it. Matching this const to the server's 16 uses the full pairable capacity (no
 *    self-limit below the ceiling) and gives the design ONE number (eager-8 + headroom, contract C2's "≈ 10–15",
 *    rounded up to the server ceiling). Freed slots are REUSED, so the cap bounds *concurrent* connections, not total.
 *  - **H7 aggregate bound (§5):** per-tunnel ≤8-frame windows × cap ⇒ worst-case ≤ 16 × 256 KB = 4 MB. Per-tunnel
 *    isolation (not a shared credit pool) avoids cross-tunnel head-of-line blocking; the cap is the aggregate ceiling.
 *  - Referenced by H7's aggregate (CYP-535) and the WS4 harness — never re-literal the number elsewhere.
 */
const val TUNNEL_POOL_CAP: Int = 16
