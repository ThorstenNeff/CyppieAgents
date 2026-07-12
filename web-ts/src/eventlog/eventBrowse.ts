// CYP-452 (P2-c.2) — the Browse VM core: pure helpers for the offline event-log inspection surface. The honesty rules
// live here (tested); the panel draws them. Distinct from the live-tail (CYP-432/448): Browse is seq-paged REST
// (`GET /api/events?…`) + server-side filter + detail + two-axis drilldown — it shares only the row + severity/glyph
// source (eventLog.ts) with the tail, never a second path (spec §7).
import type { EventSurrogate } from '../types/generated/contract'
import type { Severity } from './eventLog'

/** The server-side query axes (mirror :core EventFilter). All null = the default (unfiltered, active project). */
export interface EventFilter {
  agentId: string | null
  type: string | null // an EventType wire string
  severity: Severity | null
  correlationId: string | null
  sessionId: string | null
  since: number | null
  until: number | null
  /** CYP-94 operator cross-project lens: null = active project (server-forced), a projectId, or 'all'. */
  projectId: string | null
}

export const EMPTY_FILTER: EventFilter = {
  agentId: null,
  type: null,
  severity: null,
  correlationId: null,
  sessionId: null,
  since: null,
  until: null,
  projectId: null,
}

/** Subset-cue driver: any axis set ⇒ the view is a filtered subset, NEVER to be read as "nothing happened" (spec §3). */
export function isFilterActive(f: EventFilter): boolean {
  return (
    f.agentId !== null ||
    f.type !== null ||
    f.severity !== null ||
    f.correlationId !== null ||
    f.sessionId !== null ||
    f.since !== null ||
    f.until !== null ||
    f.projectId !== null
  )
}

/** Cross-project-view indicator driver: a non-null projectId means foreign events may be shown — never to be misread
 *  as the active project's events (spec §3, operator-only). */
export function isCrossProjectView(f: EventFilter): boolean {
  return f.projectId !== null
}

/** Build the server-side query string from the filter + paging. ONLY non-null axes are sent — this is the whole
 *  query (no client post-filter over already-loaded data, spec §3/tooth 2). projectId rides only when set. */
export function buildEventsQuery(f: EventFilter, afterSeq: number | null, limit: number): string {
  const p = new URLSearchParams()
  if (f.agentId !== null) p.set('agentId', f.agentId)
  if (f.type !== null) p.set('type', f.type)
  if (f.severity !== null) p.set('severity', f.severity)
  if (f.correlationId !== null) p.set('correlationId', f.correlationId)
  if (f.sessionId !== null) p.set('sessionId', f.sessionId)
  if (f.since !== null) p.set('since', String(f.since))
  if (f.until !== null) p.set('until', String(f.until))
  if (f.projectId !== null) p.set('projectId', f.projectId)
  if (afterSeq !== null) p.set('afterSeq', String(afterSeq))
  p.set('limit', String(limit))
  const q = p.toString()
  return q === '' ? '' : `?${q}`
}

/** Cycle an axis through its options (…→ null → opt0 → opt1 → … → null). A tap advances one step (spec §3 chips). */
export function cycleAxis<T>(current: T | null, options: readonly T[]): T | null {
  if (current === null) return options.length > 0 ? options[0] : null
  const i = options.indexOf(current)
  if (i < 0 || i === options.length - 1) return null // unknown or last → back to null (unfiltered)
  return options[i + 1]
}

/** Drilldown honesty (spec §5/§6, the sharpest tooth): each axis is enabled ONLY when the event actually carries the
 *  field — never guessed, never a fallback to the other axis. "Show the run" and "show the session" are two axes. */
export function canShowRun(event: EventSurrogate): boolean {
  return event.correlationId != null && event.correlationId !== ''
}
export function canShowSession(event: EventSurrogate): boolean {
  return event.sessionId != null && event.sessionId !== ''
}

/** A named drilldown axis + the actual field value it re-queries on (never conflated). */
export type DrilldownAxis = { kind: 'run'; correlationId: string } | { kind: 'session'; sessionId: string }

export function runDrilldown(event: EventSurrogate): DrilldownAxis | null {
  return canShowRun(event) ? { kind: 'run', correlationId: event.correlationId as string } : null
}
export function sessionDrilldown(event: EventSurrogate): DrilldownAxis | null {
  return canShowSession(event) ? { kind: 'session', sessionId: event.sessionId as string } : null
}

/** The drilldown → a filter that re-queries the ONE named axis in seq order (spec §6). Never mixes the two axes. */
export function drilldownFilter(axis: DrilldownAxis): EventFilter {
  return axis.kind === 'run'
    ? { ...EMPTY_FILTER, correlationId: axis.correlationId }
    : { ...EMPTY_FILTER, sessionId: axis.sessionId }
}

/** Fold a fetched page into the accumulated list: dedup by id, keep ascending seq order (a reload/overlap is safe). */
export function appendPage(existing: readonly EventSurrogate[], page: readonly EventSurrogate[]): EventSurrogate[] {
  const seen = new Set(existing.map((e) => e.id))
  const merged = [...existing]
  for (const e of page) if (!seen.has(e.id)) merged.push(e)
  return merged.sort((a, b) => a.seq - b.seq)
}
