package com.tneff.cyppieagents.connect

/**
 * CYP-540 (WS5) — testTag contract for the N-tunnel pool status, area `remote.pool.*` (a **sibling** to
 * [RemoteConnectTags]' `remote.connect.*` and [RemoteRevokeTags]' `remote.revoke.*`). Renders the frozen C3
 * `TunnelPoolState` (`docs/design/M2-A-ntunnel-workstream-split-and-contract.md` §4 C3). **FROZEN** by the
 * UIUX-Designer (`docs/design/tunnel-pool-status-tags.md`): Dev5 (WS5) builds against it, the WS4 harness asserts
 * against it. **Shared API with QA (CYP-7) — coordinate any change via the PO** (like the CYP-471 tags).
 *
 * Every present-condition is **fail-closed**: a real signal ⇒ the tag; no signal ⇒ absent. No phantoms, never
 * optimistic. Two deliberate freeze decisions (tags-contract §"Zwei Freeze-Entscheidungen"): ① scopeId = the
 * stable 0-based pool-slot INDEX (not the opaque rendezvousId); ② each state is its OWN presence-marker, exactly
 * one present per row (robustly assertable + fail-closed directly testable).
 */
object TunnelPoolStatusTags {
    /** THE one workspace-scoped pool-status surface. Present ⇔ remote-connected AND ≥1 emitted tunnel. Absent = INERT (spec §5.2) — never a phantom "0/cap". */
    const val CONTAINER = "remote.pool"

    /** Aggregate summary row (active/cap). Present ⇔ [CONTAINER] present. Content = literal `active/cap` (never rounded; `active<cap` ≠ deficit). */
    const val AGGREGATE = "remote.pool.aggregate"

    /** WARN qualifier — present ⇔ `aggregate.anyBackpressured == true` (amber). Absent ⇔ false (fail-closed, never a green-OK, never inferred). */
    const val AGGREGATE_BACKPRESSURED = "remote.pool.aggregate.backpressured"

    /** Per-tunnel row. scopeId = the stable **0-based pool-slot index** (NOT rendezvousId — opaque String, not testTag-charset-safe). Present ⇔ a real tunnel at this slot in the emitter. */
    fun tunnel(slot: Int) = "remote.pool.tunnel.$slot"

    /** State presence-marker. `state` ∈ {dialing, up, backpressured, down}. **Exactly ONE present** per rendered row = the current C3 state (never two, never optimistic `up`, never a marker without a real signal). */
    fun tunnelState(slot: Int, state: String) = "remote.pool.tunnel.$slot.$state"

    /** rendezvousId as asserted CONTENT (correlation) — NOT a tag segment (rid is opaque, not `[A-Za-z0-9-]+`-safe). Present ⇔ row present. */
    fun tunnelRid(slot: Int) = "remote.pool.tunnel.$slot.rid"

    /** OPTIONAL / HELD — dwell disclosure from `sinceTs`. NOT rendered by default (Anti-Over-Production); reserved for when the PO wants dwell. */
    fun tunnelSince(slot: Int) = "remote.pool.tunnel.$slot.since"
}
