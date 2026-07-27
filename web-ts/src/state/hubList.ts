// CYP-848 (CYP-807-A Multi-Hub M1) — the hub-list state: the set of hubs the client knows about, loaded from the
// control-plane's `GET /api/cp/hubs` (a frozen CYP-804 HubDescriptor array). This is the DISPLAY dimension the M2
// switcher lists (name / online / issuerTrust) — deliberately SEPARATE from the endpoint registry (net/hubRegistry.ts),
// which is the dial-address source. A HubDescriptor carries identity+status (hubId/name/online/defaultPort/dhPubKey/
// issuerTrust) but NO apiBase/wsBase: how to REACH a hub (descriptor → endpoint URL) is the federation topology, an
// arming-seam resolved at real-dial (M3), NOT here. M1 is stub-first — the descriptor array is injected; the real
// /api/cp/hubs fetch is the arming seam and is not wired yet.
import type { HubDescriptor } from '../types/generated/contract'

export interface HubListState {
  /** The known hubs, in the order the control-plane returned them. */
  readonly hubs: readonly HubDescriptor[]
  /** Whether a list has actually been loaded. Distinguishes "loaded, zero hubs" (an honest empty) from "not loaded
   *  yet" (unknown) — the switcher must never render an unknown as a confident "no hubs". */
  readonly loaded: boolean
}

/** Before any load: unknown, not an empty result. */
export const emptyHubList: HubListState = { hubs: [], loaded: false }

/**
 * Consume the hub list from the (stub-injected) /api/cp/hubs descriptor array. Pure: copies the array so a later
 * mutation of the caller's array cannot silently re-write the stored list (mirrors hubRegistryOf's defensive copy).
 * `loaded` becomes true even for an empty array — a successful empty result is honestly "zero hubs", not "unknown".
 */
export function loadHubList(descriptors: readonly HubDescriptor[]): HubListState {
  return { hubs: [...descriptors], loaded: true }
}
