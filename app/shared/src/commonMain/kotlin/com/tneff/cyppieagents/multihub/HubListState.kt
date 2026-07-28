package com.tneff.cyppieagents.multihub

import com.tneff.cyppieagents.connect.HubDescriptor

/**
 * CYP-856 (CYP-807-A Multi-Hub, Compose parity of web-ts CYP-848 M1 + CYP-852 M2) — the **hub-list state**: the set
 * of hubs the client knows about (from the control-plane `GET /hubs`, seam S-1). This is the DISPLAY dimension the
 * [HubSwitcher] lists (name / online / freshness / axis-a trust) — deliberately SEPARATE from the dial-address /
 * endpoint arming path. A [HubDescriptor] carries identity+status (hubId/name/online/defaultPort/lastSeen/dhPubKey/
 * issuerTrust) but no reachable endpoint; how to REACH a hub (descriptor → endpoint URL) is the federation topology,
 * an **arming seam** resolved at real-dial (M3/M4), NOT here.
 *
 * **THREE distinct load outcomes the switcher must render differently (empty ≠ load-error ≠ unknown), CYP-852:**
 *  - **unknown**      — `{loaded=false, loadError=false}` → not loaded yet (render nothing, never a confident "no hubs")
 *  - **honest-empty** — `{loaded=true, loadError=false, hubs=[]}` → a successful zero-hub result (an honest empty state)
 *  - **load-error**   — `{loaded=false, loadError=true}` → the `GET /hubs` load failed → Error+Retry, NEVER an empty list
 *
 * [loaded] and [loadError] are **mutually exclusive** — a failure is neither "loaded" nor "unknown". The real fetch
 * that produces the error is the arming seam; this state handling is display-only (fed via [loadHubList]/[failHubList]).
 */
data class HubListState(
    /** The known hubs, in the order the control-plane returned them. */
    val hubs: List<HubDescriptor>,
    /**
     * Whether a list has actually been loaded. Distinguishes "loaded, zero hubs" (an honest empty) from "not loaded
     * yet" (unknown) — the switcher must never render an unknown as a confident "no hubs".
     */
    val loaded: Boolean,
    /**
     * CYP-852: the last load FAILED. Mutually exclusive with [loaded] — a failure is neither "loaded" nor "unknown";
     * the switcher renders Error+Retry, never a "no hubs" empty (a failed load is not an honest zero result).
     */
    val loadError: Boolean,
) {
    companion object {
        /** Before any load: unknown — not an empty result and not an error. */
        val EMPTY: HubListState = HubListState(hubs = emptyList(), loaded = false, loadError = false)
    }
}

/**
 * Consume the hub list from the (injected, stub-first) `GET /hubs` descriptor array. Pure: copies the list so a later
 * mutation of the caller's list cannot silently re-write the stored one. [HubListState.loaded] becomes true even for
 * an empty list — a successful empty result is honestly "zero hubs", not "unknown". Clears any prior load-error.
 */
fun loadHubList(descriptors: List<HubDescriptor>): HubListState =
    HubListState(hubs = descriptors.toList(), loaded = true, loadError = false)

/**
 * CYP-852: a FAILED `GET /hubs` load. **Fail-closed** — clears any stale hubs and stays NOT loaded, so the switcher
 * renders Error+Retry rather than a misleading "no hubs" empty. Distinct from an honest empty (loaded, zero hubs).
 */
fun failHubList(): HubListState =
    HubListState(hubs = emptyList(), loaded = false, loadError = true)

/**
 * The THREE render outcomes the [HubSwitcher] branches on, derived once so the branch precedence is a single pure
 * fact (not scattered `if`s). **Precedence: error → unknown → empty → hubs** — the load-error surface ALWAYS beats
 * the empty/unknown states (fail-closed: a failed load is never rendered as "no hubs").
 */
sealed interface HubSwitcherOutcome {
    /** Not loaded yet → render nothing (never a confident "no hubs"). */
    data object Unknown : HubSwitcherOutcome

    /** The `GET /hubs` load failed → Error+Retry (never an empty). */
    data object Error : HubSwitcherOutcome

    /** Loaded, zero hubs → an honest empty state. */
    data object Empty : HubSwitcherOutcome

    /** Loaded, ≥1 hub → the switcher list. */
    data class Hubs(val hubs: List<HubDescriptor>) : HubSwitcherOutcome
}

/** Derive the render [HubSwitcherOutcome] — error beats unknown beats empty beats the list (fail-closed). */
fun HubListState.outcome(): HubSwitcherOutcome = when {
    loadError -> HubSwitcherOutcome.Error      // fail-closed: a failed load is never an empty "no hubs"
    !loaded -> HubSwitcherOutcome.Unknown      // unknown, never a confident "no hubs"
    hubs.isEmpty() -> HubSwitcherOutcome.Empty  // loaded + zero = an honest empty
    else -> HubSwitcherOutcome.Hubs(hubs)
}
