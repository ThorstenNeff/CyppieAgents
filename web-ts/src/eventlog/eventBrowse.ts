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

// ── CYP-467 (P2-c.2 fast-follow) — parity items deferred from CYP-452. No new honesty tooth; the honest carry is the
// amber-never-green polarity of the typed summaries and the "type" axis reaching the SAME server-side query. ──────────

/** Below this panel width the master + detail can't sit side by side → single-pane with back-nav (mirror :app:shared
 *  PANE_COLLAPSE_WIDTH). A width of 0 (unmeasured) is treated as wide (two-pane) — never collapse before we know. */
export const PANE_COLLAPSE_WIDTH = 600
export function isSinglePane(width: number): boolean {
  return width > 0 && width < PANE_COLLAPSE_WIDTH
}

/** The curated type-filter cycle (mirror :app:shared TYPE_CYCLE) — the event families an operator inspects in Browse.
 *  Each tap cycles the axis and re-runs the SERVER-side query (never a client post-filter — same rule as agent/severity). */
export const TYPE_CYCLE: readonly string[] = [
  'turn.start',
  'tool.call',
  'result.final',
  'error.ratelimit',
  'stall.suspected',
  'nudge.sent',
  'stall.recovered',
  'stall.escalated',
  'capability.degraded',
  'connector.optin',
]

/** CYP-94 cross-project axis sentinel (mirror :core EventFilter.PROJECT_ALL) — all authorized projects (operator-only). */
export const PROJECT_ALL = 'all'

/** The operator's project cycle: null(active, server-forced default) → each OTHER project id → 'all' → null. The
 *  active project is omitted (null already means "active") so a tap never no-ops on it. */
export function projectCycleOptions(projectIds: readonly string[], activeProjectId: string): string[] {
  return [...projectIds.filter((id) => id !== activeProjectId), PROJECT_ALL]
}

function detailRecord(detail: unknown): Record<string, unknown> | null {
  return typeof detail === 'object' && detail !== null && !Array.isArray(detail) ? (detail as Record<string, unknown>) : null
}
function intOrNull(v: unknown): number | null {
  return typeof v === 'number' && Number.isInteger(v) ? v : null
}

/** A typed one-line detail summary above the raw JSON. `warn` ⇒ amber (incomplete / unintended loss); a complete,
 *  by-design outcome is neutral — NEVER rendered green (an event log reports, it does not congratulate). */
export interface DetailSummary {
  text: string
  warn: boolean
}

/** CYP-326 §2.5 — the `compact.orchestration.done` X/N summary from its content-free CompactRunSummary detail. A
 *  timeout (`pendingAgentIds` non-empty) OR an abort renders WARN amber; a clean full run is neutral (never green).
 *  Aborted is labelled DISTINCTLY (never "timed out", never success). Absent unless both counts are present. */
export function compactDoneSummary(event: EventSurrogate): DetailSummary | null {
  if (event.type !== 'compact.orchestration.done') return null
  const d = detailRecord(event.detail)
  if (d === null) return null
  const completed = intOrNull(d.completed)
  const total = intOrNull(d.total)
  if (completed === null || total === null) return null
  const timedOut = Array.isArray(d.pendingAgentIds) ? d.pendingAgentIds.length : 0
  const aborted = d.aborted === true
  const text = aborted
    ? `Abgebrochen — ${completed} von ${total} compactet`
    : `${completed}/${total} Agenten compactet, ${timedOut} Timeout`
  return { text, warn: aborted || timedOut > 0 }
}

/** CYP-356 — the `resume.outcome` 3-stage summary from its content-free `{outcome}` detail. CONTEXT_LOST = WARN amber
 *  (an UNINTENDED memory loss — the authoritative signal, not guessed from a near-zero token count). A resume that
 *  kept context or a by-design fresh start are neutral (never warnings, never success). Absent unless `outcome`
 *  parses to a KNOWN ResumeOutcome (an unknown/newer value → no summary, never a fabricated one). */
export type ResumeOutcome = 'CONTEXT_LOST' | 'RESUMED_WITH_CONTEXT' | 'FRESH_NO_RESUME'
const KNOWN_RESUME_OUTCOMES: readonly ResumeOutcome[] = ['CONTEXT_LOST', 'RESUMED_WITH_CONTEXT', 'FRESH_NO_RESUME']
const RESUME_TEXT: Record<ResumeOutcome, DetailSummary> = {
  CONTEXT_LOST: { text: 'Kontext verloren — Sitzung ohne vorherigen Verlauf', warn: true },
  RESUMED_WITH_CONTEXT: { text: 'Sitzung mit Kontext fortgesetzt', warn: false },
  FRESH_NO_RESUME: { text: 'Neue Sitzung (kein Vorlauf)', warn: false },
}
export function resumeOutcomeSummary(event: EventSurrogate): DetailSummary | null {
  if (event.type !== 'resume.outcome') return null
  const outcome = detailRecord(event.detail)?.outcome
  if (typeof outcome !== 'string' || !KNOWN_RESUME_OUTCOMES.includes(outcome as ResumeOutcome)) return null
  return RESUME_TEXT[outcome as ResumeOutcome]
}
