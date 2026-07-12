// CYP-432 (P2-c) — the event-log Zustand store: a thin reactive shell over the pure eventLog reducers. Kept
// SEPARATE from the hub store (the event log is its own subsystem, like the window store) — smaller blast radius
// and no coupling to the comm/ACL/lifecycle state.
import { create } from 'zustand'
import { emptyEventLog, applyEventsEvent, revokeEventAccess, type EventLogState } from './eventLog'
import type { EventsWsServerEvent } from '../types/generated/contract'

export interface EventLogStore extends EventLogState {
  /** CYP-448: live-tail paused → the visible set is frozen at `pausedAtSeq`; newer events keep buffering. */
  paused: boolean
  /** the seq the view was frozen at when paused (null when live). Events with seq > this are the buffered ones. */
  pausedAtSeq: number | null
  onEventsEvent: (event: EventsWsServerEvent) => void
  /** /ws/events dropped (CYP-432): a 1008 = access revoked → fail-closed (drop buffered events, flag revoked). */
  onEventsClose: (code?: number) => void
  /** CYP-448: toggle pause. Pausing snapshots the current tip seq; resuming clears it (view goes live again). */
  togglePause: () => void
}

const NOT_PAUSED = { paused: false, pausedAtSeq: null } as const

export const useEventLogStore = create<EventLogStore>((set) => ({
  ...emptyEventLog,
  ...NOT_PAUSED,
  onEventsEvent: (event) => set((s) => applyEventsEvent(s, event)),
  // Revoke also clears any pause: a fail-closed lock is neither "live" nor a frozen tail (spec §0/§5.6).
  onEventsClose: (code) => {
    if (code === 1008) set(() => ({ ...revokeEventAccess(), ...NOT_PAUSED }))
  },
  togglePause: () =>
    set((s) =>
      s.paused
        ? { ...NOT_PAUSED }
        : { paused: true, pausedAtSeq: s.events.length ? s.events[s.events.length - 1].seq : 0 },
    ),
}))
