// CYP-848 (CYP-807-A Multi-Hub M1) — the hub-list state: the set of hubs the client knows about, loaded from the
// control-plane's `GET /api/cp/hubs` (a frozen CYP-804 HubDescriptor array). This is the DISPLAY dimension the M2
// switcher lists (name / online / issuerTrust) — deliberately SEPARATE from the endpoint registry (net/hubRegistry.ts),
// which is the dial-address source. A HubDescriptor carries identity+status (hubId/name/online/defaultPort/dhPubKey/
// issuerTrust) but NO apiBase/wsBase: how to REACH a hub (descriptor → endpoint URL) is the federation topology, an
// arming-seam resolved at real-dial (M3), NOT here. M1 is stub-first — the descriptor array is injected; the real
// /api/cp/hubs fetch is the arming seam and is not wired yet.
import type { HubDescriptor } from '../types/generated/contract'

// CYP-852 (M2): THREE distinct load outcomes the switcher must render differently (empty ≠ load-error ≠ unknown):
//   • unknown       — {loaded:false, loadError:false}  → not loaded yet (render nothing, never "no hubs")
//   • honest-empty  — {loaded:true,  loadError:false, hubs:[]} → a successful zero-hub result (honest empty state)
//   • load-error    — {loaded:false, loadError:true}  → GET /api/cp/hubs failed → Error+Retry, NEVER an empty list
// The real fetch that produces the error is the arming seam; the state handling is M2 (stub-injected via failHubList).
export interface HubListState {
  /** The known hubs, in the order the control-plane returned them. */
  readonly hubs: readonly HubDescriptor[]
  /** Whether a list has actually been loaded. Distinguishes "loaded, zero hubs" (an honest empty) from "not loaded
   *  yet" (unknown) — the switcher must never render an unknown as a confident "no hubs". */
  readonly loaded: boolean
  /** CYP-852: the last load FAILED. Mutually exclusive with `loaded` — a failure is neither "loaded" nor "unknown";
   *  the switcher renders Error+Retry, never a "no hubs" empty (a failed load is not an honest zero result). */
  readonly loadError: boolean
}

/** Before any load: unknown, not an empty result and not an error. */
export const emptyHubList: HubListState = { hubs: [], loaded: false, loadError: false }

/**
 * Consume the hub list from the (stub-injected) /api/cp/hubs descriptor array. Pure: copies the array so a later
 * mutation of the caller's array cannot silently re-write the stored list (mirrors hubRegistryOf's defensive copy).
 * `loaded` becomes true even for an empty array — a successful empty result is honestly "zero hubs", not "unknown".
 * Clears any prior load-error (a fresh successful load supersedes it).
 */
export function loadHubList(descriptors: readonly HubDescriptor[]): HubListState {
  return { hubs: [...descriptors], loaded: true, loadError: false }
}

/**
 * CYP-852: a FAILED /api/cp/hubs load. Fail-closed — clears any stale hubs and stays NOT loaded, so the switcher
 * renders Error+Retry rather than a misleading "no hubs" empty. Distinct from an honest empty (loaded, zero hubs).
 */
export function failHubList(): HubListState {
  return { hubs: [], loaded: false, loadError: true }
}
