package com.tneff.cyppieagents.multihub

/**
 * CYP-856 — the [HubSwitcher] test tags. Each of the FOUR per-hub axes gets its OWN tag namespace (name / reach /
 * lastSeen / trust-badge) so a tooth can assert they are un-conflated and that axis-c (issuerTrust) + tier are
 * ABSENT. Mirrors the web-ts `data-testid` scheme (`hub-switcher.*`).
 */
object HubSwitcherTags {
    /** CYP-856 Slice-2 — the in-workspace top-bar container (over `WindowHost`, ProjectSwitcherBar level). Absent
     *  when the list is unknown (not-loaded) — no confident-empty chrome. */
    const val BAR = "hubSwitcher.bar"

    /** The switcher nav/list container (present only when loaded with ≥1 hub). */
    const val LIST = "hub-switcher"

    /** The honest-empty state (loaded, zero hubs). */
    const val EMPTY = "hub-switcher.empty"

    /** The load-error surface container (a failed load — never the empty state). */
    const val ERROR = "hub-switcher.error"

    /** The load-error retry button. */
    const val RETRY = "hub-switcher.retry"

    /** One hub's clickable entry. */
    fun entry(hubId: String): String = "hub-switcher.entry.$hubId"

    /** Axis 1 — name/identity. */
    fun name(hubId: String): String = "hub-switcher.name.$hubId"

    /** Axis 2 — reachability (neutral online/offline; never the trust badge). */
    fun reach(hubId: String): String = "hub-switcher.reach.$hubId"

    /** Axis 4 — freshness (lastSeen). Axis 3 (hub-key trust) is the reused `HubTrustBadge`'s own tag namespace. */
    fun lastSeen(hubId: String): String = "hub-switcher.lastSeen.$hubId"
}
