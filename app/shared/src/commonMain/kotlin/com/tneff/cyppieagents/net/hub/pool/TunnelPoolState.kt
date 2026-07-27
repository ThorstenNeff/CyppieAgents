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
 *  - **CYP-611 — reverted 24→16 (Auftraggeber-approved, client-first).** The 24 bump (its earlier form) assumed the
 *    **pre-mux** 14-WS working set needed ~8 headroom. **CYP-846 muxed the 4 per-agent status feeds
 *    (lifecycle/token-usage/busy/terminal) into ONE `/ws/status`**, so the WS working set fell 14 → [WS_RESERVED_SLOTS]
 *    = 11 (7 agent + 4 singleton: status/comm/events/acl). At 16 ⇒ **15 usable data-ids** after Control (id 0); the
 *    DATA (WS) lane (15 − [CONTROL_RESERVED_SLOTS]=2 = 13) fits the 11 WS with a **2-slot reconnect-overlap margin**,
 *    and REST ([REST_DEDICATED_CONNS]=1) fits Control's reserve — the CYP-610 zero-headroom edge stays closed at the
 *    lower cap *because* the mux shrank the WS demand. Carries the 7-agent default; a larger fleet raises the cap.
 *  - **Server-anchor, client-first (transitional):** Backend's `DEFAULT_TUNNEL_POOL_CAP` is the authoritative ceiling
 *    and stays **24** until a **PL-sequenced Backend2** change follows — so transiently server(24) ≥ client(16). Safe:
 *    the **effective** pool is `min(TUNNEL_POOL_CAP, rendezvousIds.size)`, so the client self-limits to 16 (it only
 *    dials ids it holds); server ≥ client is the required invariant, never the reverse. When the server drops to 16,
 *    the two are back in lockstep with no client rework.
 *  - **H7 aggregate bound (§5):** per-tunnel ≤8-frame windows × cap ⇒ worst-case ≤ 16 × 256 KB = 4 MB. Per-tunnel
 *    isolation (not a shared credit pool) avoids cross-tunnel head-of-line blocking; the cap is the aggregate ceiling.
 *  - Referenced by H7's aggregate (CYP-535) and the WS4 harness — never re-literal the number elsewhere.
 */
const val TUNNEL_POOL_CAP: Int = 16

/**
 * CYP-610 — the WS-vs-REST **partition** of the usable data-tunnel budget (the 6-agent-remote root fix). The pool is
 * blind to WS-vs-REST; without a partition the dozen+ REST + 7 agent-WS + the singleton-WS all draw from the same
 * `min([TUNNEL_POOL_CAP], rendezvousSet)` usable ids (Control holds id 0), so idle keep-alive REST connections
 * starve the persistent WS → the 1-up/6-churn. The partition:
 *  - **[WS_RESERVED_SLOTS] = 11** long-lived WS each get their OWN tunnel — one Noise tunnel = one SOCKET (no mux,
 *    RR8/Spec §5), so they CANNOT share. **Post-CYP-846** the 7-agent-default working set is **7 agent-WS + 4
 *    singleton-WS** — the singletons are `/ws/status` (the CYP-846 mux of lifecycle/token-usage/busy/terminal-state
 *    into ONE socket), `/ws/comm`, `/ws/events` (the hub-capacity feed rides it), and `/ws/acl`. That is the
 *    "consolidate the singleton streams to relieve pool pressure without a cap bump" refactor the pre-mux 14-slot
 *    KDoc anticipated: 7 singleton → 4, so 14 → 11.
 *  - **[REST_DEDICATED_CONNS] = 1** — REST is short + sequential, so it shares ONE socket via HTTP/1.1 keep-alive
 *    (Backend: "one tunnel = one SOCKET, not one REQUEST"). Enforced client-side: the REST loopback client is capped
 *    to this many connections-per-route, so REST holds ≤ this many tunnels and can NEVER take a WS slot — the same
 *    invariant as a pool reservation, at minimal blast-radius (no pool-lane surgery, no CYP-537/556 race-teeth risk).
 *
 * At [TUNNEL_POOL_CAP]=16 (CYP-611, client-first): **15 usable** ids ⇒ the DATA (WS) lane is `15 −
 * [CONTROL_RESERVED_SLOTS]=2 = 13`, which fits the **11 WS with a 2-slot reconnect-overlap margin**; REST's 1
 * shared socket rides Control's reserve. So `11 WS + 1 REST = 12` sit inside a 13-slot data budget with headroom —
 * the zero-headroom edge (that a naive cap-16/14-WS split would hit) is closed *because* CYP-846 shrank the WS
 * demand to 11, not by inflating the cap. If the (server-anchored) cap changes, tune these two constants — no rework.
 */
const val WS_RESERVED_SLOTS: Int = 11

/** @see WS_RESERVED_SLOTS — REST's dedicated connection budget (one shared keep-alive socket). */
const val REST_DEDICATED_CONNS: Int = 1

/**
 * CYP-616 — the tunnel-acquire **lane** (client-only reservation-class, belt-and-suspenders to CYP-609). The pool is
 * otherwise WS/REST-blind; the lane lets [PooledTunnelSource.acquire] carve a **reserved break-glass headroom** the
 * DATA (WS) lane can never consume, so lifecycle-REST ([CONTROL]) can ALWAYS acquire a tunnel even when a WS-churn
 * storm has saturated the data slots (`EXHAUSTED@set held=15 inUse=15` → the "Server unreachable" on stop). The
 * transport routes the lane **by port** (two acceptors: the `restAcceptor` → [CONTROL], the `wsAcceptor` → [DATA]).
 */
enum class TunnelLane { DATA, CONTROL }

/**
 * CYP-616 — the number of pool slots reserved for the [TunnelLane.CONTROL] lane (lifecycle-REST). DATA (WS) is gated
 * at `min(cap, resolvedSet.size) − CONTROL_RESERVED_SLOTS`; CONTROL is gated at the full usable set — so the last
 * [CONTROL_RESERVED_SLOTS] slots are a break-glass headroom only lifecycle-REST can take. At [TUNNEL_POOL_CAP]=16
 * (CYP-611) the usable set is 15 (id 0 = the CP control tunnel, `drop(1)`), so the DATA (WS) lane is **13** and
 * CONTROL keeps **2** in reserve. 2 (not 1) gives a REST reconnect-overlap slot; the post-CYP-846 WS working set
 * (11, [WS_RESERVED_SLOTS]) fits under 13 with a 2-slot margin. Wired into the prod pool in `RemoteHubMode.jvm.kt`;
 * the pool's `controlReserved` ctor param defaults to 0 (no reservation) so every existing call site + the
 * CYP-537/556 race-teeth stay byte-identical (the default is the trick).
 */
const val CONTROL_RESERVED_SLOTS: Int = 2
