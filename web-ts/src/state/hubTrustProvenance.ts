// CYP-853 (CYP-807-A Multi-Hub M3) — per-hub trust PROVENANCE + the switch-first degradation rule. The honesty core of
// MC-2: under switch-first / ONE-ACTIVE-HUB (CYP-748-Q4), only the active hub holds a live connection, so only it is
// being OBSERVED. The instant a hub is switched away, observation ceases → its last-known trust lapses. Showing a cached
// `trusted` while NOT observing would be derivation-masquerading-as-observation — the exact honesty bug forbidden here.
//
// ★ PROVENANCE, not a boolean (PL refinement): the map records WHEN each trust was observed (observedAt), not merely
// that it was. The immediate-on-switch lapse does NOT threshold on the timestamp — but the timestamp is the honest
// provenance the STALE-vs-unknown split rests on, AND it makes a future freshness-window (Team-1 [TF], if ever) a purely
// ADDITIVE threshold comparison behind the same data. We build the SEAM, not the window.
import type { HubTrustState } from '../connector/hubTrustModel'
import type { HubId } from '../net/hubRegistry'

/** A trust value that was actually OBSERVED over a live connection to a hub, with its observation time (provenance). */
export interface ObservedTrust {
  readonly trust: HubTrustState
  /** Epoch ms of the observation. Honest provenance; NOT thresholded under the immediate-on-switch lapse (the seam for
   *  a possible future freshness window, not the window itself). */
  readonly observedAt: number
}

/** hubId → its last observed trust. Only hubs ever observed appear; absence = never observed. */
export type TrustProvenance = Readonly<Record<HubId, ObservedTrust>>

export const emptyTrustProvenance: TrustProvenance = {}

/**
 * Record an observation made over the live connection to `hubId`. Pure — returns a new map. The caller supplies
 * `observedAt` (the app stamps Date.now(); tests pass an explicit time) so this stays deterministic/testable.
 */
export function recordObservation(
  p: TrustProvenance,
  hubId: HubId,
  trust: HubTrustState,
  observedAt: number,
): TrustProvenance {
  return { ...p, [hubId]: { trust, observedAt } }
}

/**
 * The trust to DISPLAY for `hubId` in the switcher, given which hub is active (MC-2, switch-first immediate lapse):
 *   • the ACTIVE hub → its live observed trust (we are observing it now); UNKNOWN until first observed.
 *   • an INACTIVE hub is NOT observed → last-known lapses:
 *       was TRUSTED  → STALE    (had trust, no longer fresh — NEVER keep showing cached `trusted`);
 *       was REJECTED → REJECTED (a fail-closed negative is preserved, never softened to stale/unknown);
 *       never observed / was pending|unknown|stale → UNKNOWN (nothing affirmative to show without observation).
 * Fail-closed throughout: absence ⇒ UNKNOWN, cached-trusted ⇒ STALE, never a fabricated affirmative.
 */
export function displayedTrust(p: TrustProvenance, hubId: HubId, activeHubId: HubId): HubTrustState {
  const obs = p[hubId]
  if (hubId === activeHubId) return obs?.trust ?? 'UNKNOWN' // active: live observation (or unknown until first observed)
  if (obs === undefined) return 'UNKNOWN' // inactive, never observed
  if (obs.trust === 'TRUSTED') return 'STALE' // inactive, was trusted → stale (immediate lapse; never cached-trusted)
  if (obs.trust === 'REJECTED') return 'REJECTED' // inactive, was rejected → keep the fail-closed negative
  return 'UNKNOWN' // inactive, was pending/unknown/stale → nothing fresh to affirm
}
