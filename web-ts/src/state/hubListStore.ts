// CYP-848 (Multi-Hub M1) — the hub-list Zustand store: a thin reactive shell over the pure hubList model. Kept
// SEPARATE from the hub store (the known-hubs set is its own subsystem, like the event-log store) — smaller blast
// radius and no coupling to the active hub's comm/ACL/lifecycle state. The M2 switcher subscribes here; M1 feeds it
// from the stub-injected descriptor array (real /api/cp/hubs fetch = arming seam).
import { create } from 'zustand'
import { emptyHubList, loadHubList, failHubList, type HubListState } from './hubList'
import type { HubDescriptor } from '../types/generated/contract'

export interface HubListStore extends HubListState {
  /** Replace the known-hubs list from a loaded (stub-injected in M1) /api/cp/hubs descriptor array. */
  setHubList: (descriptors: readonly HubDescriptor[]) => void
  /** CYP-852: mark the /api/cp/hubs load as FAILED → the switcher renders Error+Retry, not a "no hubs" empty. */
  failHubListLoad: () => void
}

export const useHubListStore = create<HubListStore>((set) => ({
  ...emptyHubList,
  setHubList: (descriptors) => set(() => loadHubList(descriptors)),
  failHubListLoad: () => set(() => failHubList()),
}))
