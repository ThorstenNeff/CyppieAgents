// CYP-432 (P2-c) — the event-log VM core: pure reducers over the /ws/events feed (EventsWsServerEvent = Event |
// Caughtup). The feed replays history then sends Caughtup to mark the live boundary. Honesty rules live here
// (tested), the renderer just draws them:
//   - dedup by EventSurrogate.id (a reconnect replay is idempotent), kept sorted by the global `seq`.
//   - GAP detection ("no silent caps", PRD §3.3 / event-log-tokens.json event.gap.dropped): a non-contiguous seq
//     means events were dropped — surfaced as an explicit gap row, NEVER silently skipped.
import type { EventsWsServerEvent, EventSurrogate } from '../types/generated/contract'

export type Severity = EventSurrogate['severity'] // 'debug' | 'info' | 'warn' | 'error'

/** CYP-448: the live-tail is a bounded ring — a runaway stream can't grow the buffer without bound. When the
 *  cap is exceeded the OLDEST events are dropped and counted into `trimmed` → surfaced as a marker, NEVER a
 *  silent cap (spec §5.2, `event_tail_trimmed`). */
export const MAX_LIVE_EVENTS = 1000

export interface EventLogState {
  /** deduped by id, sorted ascending by seq; capped at MAX_LIVE_EVENTS (oldest dropped → `trimmed`). */
  events: readonly EventSurrogate[]
  /** the server has replayed history and signalled Caughtup → we're live. */
  caughtUp: boolean
  /** the /ws/events access was revoked (WS 1008) → fail-closed: stop showing data, no reconnect (CYP-432). */
  accessRevoked: boolean
  /** CYP-448: count of oldest events dropped by the bounded ring — disclosed, never a silent cap (spec §5.2). */
  trimmed: number
}

export const emptyEventLog: EventLogState = { events: [], caughtUp: false, accessRevoked: false, trimmed: 0 }

/** Fail-closed on an access revoke (WS 1008): drop the buffered events (no stale bodies linger) and flag revoked. */
export function revokeEventAccess(): EventLogState {
  return { events: [], caughtUp: false, accessRevoked: true, trimmed: 0 }
}

export function applyEventsEvent(state: EventLogState, e: EventsWsServerEvent): EventLogState {
  if (e.type === 'caughtup') return state.caughtUp ? state : { ...state, caughtUp: true }
  const ev = e.event
  if (state.events.some((x) => x.id === ev.id)) return state // idempotent: drop a replayed duplicate
  let events = [...state.events, ev].sort((a, b) => a.seq - b.seq)
  let trimmed = state.trimmed
  if (events.length > MAX_LIVE_EVENTS) {
    trimmed += events.length - MAX_LIVE_EVENTS // disclose the drop (event_tail_trimmed), never silently cap
    events = events.slice(events.length - MAX_LIVE_EVENTS)
  }
  return { ...state, events, trimmed }
}

/** CYP-448: the paused/live split of the tail. Pausing freezes the visible set at `pausedAtSeq`; events beyond it
 *  keep arriving into the ring and are counted as `bufferedCount` (spec §3 `bufferedCount`, §5.6 pausiert≠live).
 *  A frozen view is NEVER rendered as live — the caller shows `pausedIndicator`, not `liveIndicator`. */
export interface TailView {
  visible: readonly EventSurrogate[]
  bufferedCount: number
}

export function tailView(
  events: readonly EventSurrogate[],
  paused: boolean,
  pausedAtSeq: number | null,
): TailView {
  if (!paused || pausedAtSeq === null) return { visible: events, bufferedCount: 0 }
  const visible = events.filter((e) => e.seq <= pausedAtSeq)
  return { visible, bufferedCount: events.length - visible.length }
}

/** CYP-448 — the per-TYPE group glyph, ported 1:1 from :app:shared EventVisuals.groupGlyph() (keyed on the wire
 *  `type` string, EventModel.EventType.wire). A scan aid ONLY: the type text (monospace, incl. the raw UNKNOWN
 *  string) carries the meaning; severity carries the alarm. An unmapped/newer wire string → ⓘ (UNKNOWN group). */
const TYPE_GLYPHS: Readonly<Record<string, string>> = {
  'turn.start': '⚙', 'turn.end': '⚙', 'tool.call': '⚙', 'tool.result': '⚙', 'file.changed': '⚙', 'result.final': '⚙',
  'context.usage': '▦', 'compact.triggered': '▦', 'compact.completed': '▦', 'compact.prepare.sent': '▦',
  'compact.request.sent': '▦', 'compact.orchestration.done': '▦',
  'hook.fired': '⤵',
  'error.model': '⚠', 'error.tool': '⚠', 'error.ratelimit': '⚠', 'process.exit': '⚠', 'timeout': '⚠',
  'ws.disconnect': '⚠', 'log.dropped': '⚠',
  'agent.spawned': '⏻', 'agent.restarted': '⏻', 'agent.stopped': '⏻', 'session.recycled': '⏻', 'resume.outcome': '⏻',
  'comm.sent': '⇄', 'comm.received': '⇄',
  'stall.suspected': '☂', 'nudge.sent': '☂', 'stall.recovered': '☂', 'stall.escalated': '☂',
  'capability.degraded': '▽',
  'connector.optin': '⇆',
  'capacity.changed': '▤', 'spawn.rejected': '▤',
  'unknown': 'ⓘ',
}

export function typeGlyph(type: string): string {
  return TYPE_GLYPHS[type] ?? 'ⓘ'
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
