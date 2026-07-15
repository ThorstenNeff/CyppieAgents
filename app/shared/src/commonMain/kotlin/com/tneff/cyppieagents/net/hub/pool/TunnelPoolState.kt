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
 *  - **Single-sourced to the SERVER cap (WS1↔WS2 convergence):** Backend's `DEFAULT_TUNNEL_POOL_CAP` is the
 *    **authoritative structural ceiling** — the CP derives **exactly** that many opaque rendezvous-ids (C4), so at
 *    most that many tunnels can ever pair (the per-operator DoS floor). The **effective** pool is therefore
 *    `min(TUNNEL_POOL_CAP, rendezvousIds.size)`: the client is structurally bound by the resolved set-size — it can
 *    only dial ids the CP handed it. Matching this const to the server value uses the full pairable capacity (no
 *    self-limit below the ceiling). Freed slots are REUSED, so the cap bounds *concurrent* connections, not total.
 *  - **CYP-611 — raised 16→24 (human-authorized DoS-envelope bump), lockstep with server `DEFAULT_TUNNEL_POOL_CAP=24`
 *    (server ≥ client).** 24 ⇒ 23 usable data-ids after Control ([WS_RESERVED_SLOTS]=14 WS + [REST_DEDICATED_CONNS]=1
 *    REST + ~8 headroom, carries to ~16 agents). The CYP-610 REST-vs-WS fix is correct at any cap ≥ 15; 24 just adds
 *    headroom so a WS reconnect overlap never contends.
 *  - **H7 aggregate bound (§5):** per-tunnel ≤8-frame windows × cap ⇒ worst-case ≤ 24 × 256 KB = 6 MB. Per-tunnel
 *    isolation (not a shared credit pool) avoids cross-tunnel head-of-line blocking; the cap is the aggregate ceiling.
 *  - Referenced by H7's aggregate (CYP-535) and the WS4 harness — never re-literal the number elsewhere.
 */
const val TUNNEL_POOL_CAP: Int = 24

/**
 * CYP-610 — the WS-vs-REST **partition** of the usable data-tunnel budget (the 6-agent-remote root fix). The pool is
 * blind to WS-vs-REST; without a partition the dozen+ REST + 7 agent-WS + 7 singleton-WS all draw from the same
 * `min([TUNNEL_POOL_CAP], rendezvousSet)` usable ids (Control holds id 0), so idle keep-alive REST connections
 * starve the persistent WS → the 1-up/6-churn. The partition:
 *  - **[WS_RESERVED_SLOTS] = 14** long-lived WS each get their OWN tunnel — one Noise tunnel = one SOCKET (no mux,
 *    RR8/Spec §5), so the 7 agent-WS + 7 singleton-WS (busy/lifecycle/terminal-state/token-usage/comm/events/acl)
 *    CANNOT share; 14 is the 7-agent-default working set. (Future Tage-refactor: consolidate the 7 singleton streams
 *    to 1–2 muxed → ~9 WS, relieving pool pressure without a cap bump — does not change THIS fix.)
 *  - **[REST_DEDICATED_CONNS] = 1** — REST is short + sequential, so it shares ONE socket via HTTP/1.1 keep-alive
 *    (Backend: "one tunnel = one SOCKET, not one REQUEST"). Enforced client-side: the REST loopback client is capped
 *    to this many connections-per-route, so REST holds ≤ this many tunnels and can NEVER take a WS slot — the same
 *    invariant as a pool reservation, at minimal blast-radius (no pool-lane surgery, no CYP-537/556 race-teeth risk).
 *
 * At [TUNNEL_POOL_CAP]=24 (CYP-611): 23 usable ⇒ `1 REST + 14 WS + ~8 headroom` (carries to ~16 agents; the WS
 * reconnect-overlap edge that a zero-headroom 15-budget would briefly contend is gone). The fix is correct at any
 * cap ≥ 15; if the server cap changes ([TUNNEL_POOL_CAP] is server-anchored, `DEFAULT_TUNNEL_POOL_CAP`), tune these
 * two constants — no rework.
 */
const val WS_RESERVED_SLOTS: Int = 14

/** @see WS_RESERVED_SLOTS — REST's dedicated connection budget (one shared keep-alive socket). */
const val REST_DEDICATED_CONNS: Int = 1

/**
 * CYP-616 — the tunnel-acquire **lane** (client-only reservation-class, belt-and-suspenders to CYP-609). The pool is
 * otherwise WS/REST-blind; the lane lets [PooledTunnelSource.acquire] carve a **reserved break-glass headroom** the
 * DATA (WS) lane can never consume, so lifecycle-REST ([CONTROL]) can ALWAYS acquire a tunnel even when a WS-churn
 * storm has saturated the data slots (`EXHAUSTED@set held=23 inUse=23` → the "Server unreachable" on stop). The
 * transport routes the lane **by port** (two acceptors: the `restAcceptor` → [CONTROL], the `wsAcceptor` → [DATA]).
 */
enum class TunnelLane { DATA, CONTROL }

/**
 * CYP-616 — the number of pool slots reserved for the [TunnelLane.CONTROL] lane (lifecycle-REST). DATA (WS) is gated
 * at `min(cap, resolvedSet.size) − CONTROL_RESERVED_SLOTS`; CONTROL is gated at the full usable set — so the last
 * [CONTROL_RESERVED_SLOTS] slots are a break-glass headroom only lifecycle-REST can take. At [TUNNEL_POOL_CAP]=24 the
 * usable set is 23 (id 0 = the CP control tunnel, `drop(1)`), so WS share **21** and CONTROL keeps **2** in reserve.
 * 2 (not 1) gives a REST reconnect-overlap slot; the WS working set (14, [WS_RESERVED_SLOTS]) fits comfortably under 21.
 * Wired into the prod pool in `RemoteHubMode.jvm.kt`; the pool's `controlReserved` ctor param defaults to 0 (no
 * reservation) so every existing call site + the CYP-537/556 race-teeth stay byte-identical (the default is the trick).
 */
const val CONTROL_RESERVED_SLOTS: Int = 2
