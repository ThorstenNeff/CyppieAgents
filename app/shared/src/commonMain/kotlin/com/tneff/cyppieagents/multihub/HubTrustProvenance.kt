package com.tneff.cyppieagents.multihub

import com.tneff.cyppieagents.model.HubTrustState

/**
 * CYP-865 (Compose-M3-Mirror of web-ts CYP-853) — per-hub trust **PROVENANCE** + the switch-first degradation rule
 * (the honesty core of MC-2). Under switch-first / ONE-ACTIVE-HUB (CYP-748-Q4) only the active hub holds a live
 * connection, so only it is being OBSERVED. The instant a hub is switched away, observation ceases → its last-known
 * trust lapses. Showing a cached `TRUSTED` while NOT observing would be derivation-masquerading-as-observation — the
 * exact honesty bug forbidden here.
 *
 * ★ PROVENANCE, not a boolean: the map records WHEN each trust was observed ([ObservedTrust.observedAt]), not merely
 * that it was. The immediate-on-switch lapse does NOT threshold on the timestamp — but the timestamp is the honest
 * provenance the STALE-vs-unknown split rests on, and makes a future freshness-window a purely ADDITIVE threshold
 * behind the same data. **We build the SEAM, not the window.** Pure — no live connection here (arming DARK).
 */

/** A trust value that was actually OBSERVED over a live connection to a hub, with its observation time (provenance). */
data class ObservedTrust(
    val trust: HubTrustState,
    /** Epoch ms of the observation. Honest provenance; NOT thresholded under the immediate-on-switch lapse. */
    val observedAt: Long,
)

/** hubId → its last observed trust. Only hubs ever observed appear; absence = never observed. */
typealias TrustProvenance = Map<String, ObservedTrust>

/** Before any observation: nothing observed. */
val emptyTrustProvenance: TrustProvenance = emptyMap()

/**
 * Record an observation made over the live connection to [hubId]. **Pure** — returns a new map. The caller supplies
 * [observedAt] (the app stamps the wall clock; tests pass an explicit time) so this stays deterministic/testable.
 */
fun recordObservation(
    provenance: TrustProvenance,
    hubId: String,
    trust: HubTrustState,
    observedAt: Long,
): TrustProvenance = provenance + (hubId to ObservedTrust(trust, observedAt))

/**
 * The trust to DISPLAY for [hubId] in the switcher, given which hub is active (MC-2, switch-first immediate lapse):
 *  - the **ACTIVE** hub → its live observed trust (we are observing it now); [HubTrustState.UNKNOWN] until first observed.
 *  - an **INACTIVE** hub is NOT observed → last-known lapses:
 *      - was [HubTrustState.TRUSTED]  → [HubTrustState.STALE]    (had trust, no longer fresh — NEVER keep showing cached trusted);
 *      - was [HubTrustState.REJECTED] → [HubTrustState.REJECTED] (a fail-closed negative is preserved, never softened);
 *      - never observed / was pending|unknown|stale → [HubTrustState.UNKNOWN] (nothing affirmative to show without observation).
 *
 * Fail-closed throughout: absence ⇒ UNKNOWN, cached-trusted ⇒ STALE, never a fabricated affirmative.
 */
fun displayedTrust(provenance: TrustProvenance, hubId: String, activeHubId: String): HubTrustState {
    val observed = provenance[hubId]
    if (hubId == activeHubId) return observed?.trust ?: HubTrustState.UNKNOWN // active: live observation (or unknown)
    if (observed == null) return HubTrustState.UNKNOWN // inactive, never observed
    return when (observed.trust) {
        HubTrustState.TRUSTED -> HubTrustState.STALE // inactive, was trusted → stale (immediate lapse; never cached-trusted)
        HubTrustState.REJECTED -> HubTrustState.REJECTED // inactive, was rejected → keep the fail-closed negative
        else -> HubTrustState.UNKNOWN // inactive, was pending/unknown/stale → nothing fresh to affirm
    }
}
