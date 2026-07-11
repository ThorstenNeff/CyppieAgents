// CYP-432 (P2-c) — the event-log VM core: pure reducers over the /ws/events feed (EventsWsServerEvent = Event |
// Caughtup). The feed replays history then sends Caughtup to mark the live boundary. Honesty rules live here
// (tested), the renderer just draws them:
//   - dedup by EventSurrogate.id (a reconnect replay is idempotent), kept sorted by the global `seq`.
//   - GAP detection ("no silent caps", PRD §3.3 / event-log-tokens.json event.gap.dropped): a non-contiguous seq
//     means events were dropped — surfaced as an explicit gap row, NEVER silently skipped.
import type { EventsWsServerEvent, EventSurrogate } from '../types/generated/contract'

export type Severity = EventSurrogate['severity'] // 'debug' | 'info' | 'warn' | 'error'

export interface EventLogState {
  /** deduped by id, sorted ascending by seq. */
  events: readonly EventSurrogate[]
  /** the server has replayed history and signalled Caughtup → we're live. */
  caughtUp: boolean
  /** the /ws/events access was revoked (WS 1008) → fail-closed: stop showing data, no reconnect (CYP-432). */
  accessRevoked: boolean
}

export const emptyEventLog: EventLogState = { events: [], caughtUp: false, accessRevoked: false }

/** Fail-closed on an access revoke (WS 1008): drop the buffered events (no stale bodies linger) and flag revoked. */
export function revokeEventAccess(): EventLogState {
  return { events: [], caughtUp: false, accessRevoked: true }
}

export function applyEventsEvent(state: EventLogState, e: EventsWsServerEvent): EventLogState {
  if (e.type === 'caughtup') return state.caughtUp ? state : { ...state, caughtUp: true }
  const ev = e.event
  if (state.events.some((x) => x.id === ev.id)) return state // idempotent: drop a replayed duplicate
  const events = [...state.events, ev].sort((a, b) => a.seq - b.seq)
  return { ...state, events }
}

/** A render row: an event, or an explicit gap where the seq jumped (dropped events — never hidden). */
export type EventRow =
  | { kind: 'event'; event: EventSurrogate }
  | { kind: 'gap'; afterSeq: number; count: number }

/** Fold the sorted events into render rows, inserting a gap row wherever `seq` is non-contiguous. */
export function eventRows(events: readonly EventSurrogate[]): EventRow[] {
  const rows: EventRow[] = []
  let prev: number | null = null
  for (const ev of events) {
    if (prev !== null && ev.seq > prev + 1) rows.push({ kind: 'gap', afterSeq: prev, count: ev.seq - prev - 1 })
    rows.push({ kind: 'event', event: ev })
    prev = ev.seq
  }
  return rows
}

/** Severity glyph — colour is never the sole signal (WCAG 1.4.1); ported from :app:shared EventVisuals.glyph(). */
export function severityGlyph(sev: Severity): string {
  switch (sev) {
    case 'error':
      return '⚠'
    case 'warn':
      return '▲'
    case 'info':
      return 'ⓘ'
    case 'debug':
      return '·'
  }
}

/** Severity text label (the meaning, carried in text so colour is never alone). Severity is a quick filter, NOT a
 *  correctness verdict — info/debug are quiet, not "good" (event-log-tokens.json note). */
export function severityLabel(sev: Severity): string {
  switch (sev) {
    case 'error':
      return 'Fehler'
    case 'warn':
      return 'Warnung'
    case 'info':
      return 'Info'
    case 'debug':
      return 'Debug'
  }
}
