// CYP-432 (P2-c) — the event-log Zustand store: a thin reactive shell over the pure eventLog reducers. Kept
// SEPARATE from the hub store (the event log is its own subsystem, like the window store) — smaller blast radius
// and no coupling to the comm/ACL/lifecycle state.
import { create } from 'zustand'
import { emptyEventLog, applyEventsEvent, revokeEventAccess, type EventLogState } from './eventLog'
import type { EventsWsServerEvent } from '../types/generated/contract'

export interface EventLogStore extends EventLogState {
  onEventsEvent: (event: EventsWsServerEvent) => void
  /** /ws/events dropped (CYP-432): a 1008 = access revoked → fail-closed (drop buffered events, flag revoked). */
  onEventsClose: (code?: number) => void
}

export const useEventLogStore = create<EventLogStore>((set) => ({
  ...emptyEventLog,
  onEventsEvent: (event) => set((s) => applyEventsEvent(s, event)),
  onEventsClose: (code) => {
    if (code === 1008) set(revokeEventAccess)
  },
}))
