// CYP-452 (P2-c.2) — the Event-Log Browse panel: offline inspection over the same operator-gated, mount-gated content-
// free metadata as the live-tail. The REAL scope boundary is server-side (resolveEventScope + masking); this client
// mount-gate is defence-in-depth + product-scoping (App mounts it ONLY for an operator — omission), NOT a leak barrier.
// Distinct from the tail: seq-paged REST + server-side filter + detail + two-axis drilldown. Shares the ONE EventRow +
// severity/glyph source (spec §7). The load-bearing honesty:
//   - server-side query per filter/drilldown tap (never a client post-filter over loaded data — spec §3/tooth 2);
//   - filterActive subset-cue + cross-project indicator (absence ≠ all-clear — spec §3/tooth 3);
//   - error BEATS empty: a failed first-page → error+retry, NOT the "no events" state (CYP-288/tooth 4);
//   - no invented correlation: showRun/showSession enabled ONLY when the event carries the field, two axes never
//     conflated (spec §5/§6/tooth 1);
//   - sourceTs = "observed" (informative), ordering is seq (tooth 6); content-free detail JSON as-is (tooth 8).
// CYP-467 (fast-follow) adds the deferred parity items — typed compact/resume WARN-amber detail summaries, the
// type/project/timeWindow filter axes, and the <600px single-pane back-nav — no new honesty tooth (polish/parity).
import { useEffect, useMemo, useRef, useState } from 'react'
import type { EventPage, EventSurrogate, Project } from '../types/generated/contract'
import { RestError } from '../net/rest'
import { EventRow } from './EventRow'
import { LoadErrorRetry } from '../ui/LoadErrorRetry'
import { severityLabel, typeGlyph, type Severity } from './eventLog'
import { formatLocalHhMm } from '../agentview/transcriptTime'
import {
  EMPTY_FILTER,
  isFilterActive,
  isCrossProjectView,
  cycleAxis,
  canShowRun,
  canShowSession,
  runDrilldown,
  sessionDrilldown,
  drilldownFilter,
  appendPage,
  isSinglePane,
  TYPE_CYCLE,
  PROJECT_ALL,
  projectCycleOptions,
  compactDoneSummary,
  resumeOutcomeSummary,
  type EventFilter,
  type DrilldownAxis,
} from './eventBrowse'

const PAGE_LIMIT = 100
const SEVERITIES: readonly Severity[] = ['debug', 'info', 'warn', 'error']

export interface EventBrowsePanelProps {
  getEvents: (filter: EventFilter, afterSeq: number | null, limit: number) => Promise<EventPage>
  /** agent-chip options (the roster ids). */
  agentIds: readonly string[]
  /** CYP-467/94: the operator's projects + active id drive the cross-project axis. Empty (default) → no project chip. */
  projects?: readonly Project[]
  activeProjectId?: string
}

export function EventBrowsePanel({ getEvents, agentIds, projects = [], activeProjectId = '' }: EventBrowsePanelProps) {
  const [filter, setFilter] = useState<EventFilter>(EMPTY_FILTER)
  const [drilldown, setDrilldown] = useState<DrilldownAxis | null>(null)
  const [events, setEvents] = useState<readonly EventSurrogate[]>([])
  const [nextAfterSeq, setNextAfterSeq] = useState<number | null>(null)
  const [hasMore, setHasMore] = useState(false)
  const [loading, setLoading] = useState(false)
  const [firstPageError, setFirstPageError] = useState(false)
  const [accessRevoked, setAccessRevoked] = useState(false)
  const [selectedId, setSelectedId] = useState<string | null>(null)

  // CYP-467: measure the panel's own width so master+detail collapse to a single pane below PANE_COLLAPSE_WIDTH.
  // A width of 0 (unmeasured, e.g. jsdom) stays two-pane — never collapse before we know the size.
  const rootRef = useRef<HTMLDivElement>(null)
  const [paneWidth, setPaneWidth] = useState(0)
  useEffect(() => {
    const el = rootRef.current
    if (el === null) return
    if (typeof ResizeObserver !== 'undefined') {
      const ro = new ResizeObserver((entries) => {
        for (const e of entries) setPaneWidth(e.contentRect.width)
      })
      ro.observe(el)
      return () => ro.disconnect()
    }
    const measure = () => setPaneWidth(el.clientWidth)
    measure()
    window.addEventListener('resize', measure)
    return () => window.removeEventListener('resize', measure)
  }, [])
  const singlePane = isSinglePane(paneWidth)

  // The effective query = the drilldown's single-axis filter when drilled in, else the filter bar.
  const effectiveFilter = useMemo(() => (drilldown ? drilldownFilter(drilldown) : filter), [drilldown, filter])

  // CYP-489: hold getEvents in a ref so a parent re-render (which hands a NEW inline-closure prop) does NOT re-key the
  // first-page effect and refetch. The effect keys ONLY on the effective query; the ref keeps the fetch fn current for
  // the next real query (a filter/drilldown tap). Pure network hygiene — the server-side query/scope is unchanged.
  const getEventsRef = useRef(getEvents)
  getEventsRef.current = getEvents

  // Load the FIRST page whenever the effective query changes (a filter/drilldown tap → a new server query).
  const reqSeq = useRef(0)
  useEffect(() => {
    const req = ++reqSeq.current
    setLoading(true)
    setFirstPageError(false)
    getEventsRef.current(effectiveFilter, null, PAGE_LIMIT)
      .then((page) => {
        if (req !== reqSeq.current) return // a newer query superseded this one
        setEvents(page.events)
        setNextAfterSeq(page.nextAfterSeq ?? null)
        setHasMore(page.hasMore === true)
        setSelectedId(null)
        setLoading(false)
      })
      .catch((e) => {
        if (req !== reqSeq.current) return
        // Runtime access revoke (403) → fail-closed: drop everything, lock the surface (server-authoritative; the
        // server stops scoping events to us — the client just stops showing stale ones, defence-in-depth).
        if (e instanceof RestError && e.status === 403) {
          setEvents([])
          setAccessRevoked(true)
        } else {
          setFirstPageError(true) // error BEATS empty (tooth 4)
        }
        setLoading(false)
      })
  }, [effectiveFilter])

  const loadMore = () => {
    if (nextAfterSeq === null || loading) return
    setLoading(true)
    const req = reqSeq.current // loadMore belongs to the current query
    getEventsRef.current(effectiveFilter, nextAfterSeq, PAGE_LIMIT)
      .then((page) => {
        if (req !== reqSeq.current) return
        setEvents((prev) => appendPage(prev, page.events)) // a failed loadMore keeps the loaded table (no wipe)
        setNextAfterSeq(page.nextAfterSeq ?? null)
        setHasMore(page.hasMore === true)
        setLoading(false)
      })
      .catch(() => setLoading(false)) // keep the already-loaded rows; no error surface replacing data
  }

  const setAxis = <K extends keyof EventFilter>(key: K, value: EventFilter[K]) => {
    setDrilldown(null) // a filter tap exits any drilldown
    setFilter((f) => ({ ...f, [key]: value }))
  }

  const selected = useMemo(() => events.find((e) => e.id === selectedId) ?? null, [events, selectedId])

  if (accessRevoked) {
    return (
      <p className="event-browse-revoked" role="alert" data-testid="eventBrowse.accessRevoked">
        Zugriff entzogen — der Ereignis-Browser ist gesperrt.
      </p>
    )
  }

  // CYP-467 single-pane: below PANE_COLLAPSE_WIDTH a selected event REPLACES the master (its Back returns to it).
  const detailPane =
    selected !== null ? <DetailPane event={selected} onBack={() => setSelectedId(null)} onDrill={setDrilldown} /> : null
  const showDetailOnly = singlePane && detailPane !== null

  return (
    <div
      className="event-browse"
      ref={rootRef}
      data-testid="eventBrowse"
      data-single-pane={singlePane ? 'true' : undefined}
    >
      {showDetailOnly ? (
        detailPane
      ) : (
        <>
          <FilterBar
            filter={filter}
            agentIds={agentIds}
            projects={projects}
            activeProjectId={activeProjectId}
            drilldown={drilldown}
            onCycle={setAxis}
            onClearDrilldown={() => setDrilldown(null)}
          />

          <div className="event-browse-body">
            <div className="event-browse-master">
              {firstPageError ? (
                // CYP-288 follow-up: this surface was the ONE remaining inline copy of the load-error shape. It
                // now uses the shared primitive, so a future change to the copy or a11y reaches it too — the
                // divergence risk was the point, not the markup. The testid stays `eventBrowse.error` (the
                // primitive stamps `${testId}` + `${testId}.retry`, byte-identical to what shipped), so no test
                // harness breaks: the naming convention can align later, once Tester2 confirms nothing depends on
                // it. Renaming a shared testid unilaterally is the drift this consolidation is meant to reduce.
                <LoadErrorRetry testId="eventBrowse.error" onRetry={() => setFilter((f) => ({ ...f }))} />
              ) : events.length === 0 && !loading ? (
                <p className="event-browse-empty" role="status" data-testid="eventBrowse.empty">
                  Keine Events.
                </p>
              ) : (
                <ol className="event-browse-table transcript-scroll" data-testid="eventBrowse.table">
                  {events.map((e, i) => (
                    <EventRow
                      key={e.id}
                      event={e}
                      testid={`eventBrowse.row.${i}`}
                      showProject={isCrossProjectView(filter)}
                      onSelect={() => setSelectedId(e.id)}
                    />
                  ))}
                </ol>
              )}
              {hasMore && !firstPageError && (
                <button type="button" className="event-browse-more" data-testid="eventBrowse.loadMore" onClick={loadMore} disabled={loading}>
                  Mehr laden
                </button>
              )}
            </div>

            {/* two-pane: detail sits beside the master. single-pane handles it above (detailPane replaces master). */}
            {!singlePane && detailPane}
          </div>
        </>
      )}
    </div>
  )
}

function projectChipLabel(projectId: string | null, projects: readonly Project[]): string {
  if (projectId === null) return '—'
  if (projectId === PROJECT_ALL) return 'Alle Projekte'
  return projects.find((p) => p.id === projectId)?.name ?? projectId
}

function FilterBar({
  filter,
  agentIds,
  projects,
  activeProjectId,
  drilldown,
  onCycle,
  onClearDrilldown,
}: {
  filter: EventFilter
  agentIds: readonly string[]
  projects: readonly Project[]
  activeProjectId: string
  drilldown: DrilldownAxis | null
  onCycle: <K extends keyof EventFilter>(key: K, value: EventFilter[K]) => void
  onClearDrilldown: () => void
}) {
  const projectCycle = projectCycleOptions(projects.map((p) => p.id), activeProjectId)
  return (
    <div className="event-browse-filterbar" data-testid="eventBrowse.filterBar">
      {/* CYP-94 cross-project axis (operator-only) — only shown when the operator has a project list to cycle. */}
      {projects.length > 0 && (
        <button
          type="button"
          data-testid="eventBrowse.filter.project"
          aria-label={`Filter Projekt: ${projectChipLabel(filter.projectId, projects)}`}
          onClick={() => onCycle('projectId', cycleAxis(filter.projectId, projectCycle))}
        >
          Projekt: {projectChipLabel(filter.projectId, projects)}
        </button>
      )}
      <button
        type="button"
        data-testid="eventBrowse.filter.agent"
        aria-label={`Filter Agent: ${filter.agentId ?? 'alle'}`}
        onClick={() => onCycle('agentId', cycleAxis(filter.agentId, agentIds))}
      >
        Agent: {filter.agentId ?? '—'}
      </button>
      {/* type axis: cycles the curated families through the SAME server-side query (never a client post-filter). The
          glyph is a scan aid; the wire type text carries the meaning (colour/glyph never alone). */}
      <button
        type="button"
        data-testid="eventBrowse.filter.type"
        aria-label={`Filter Typ: ${filter.type ?? 'alle'}`}
        onClick={() => onCycle('type', cycleAxis(filter.type, TYPE_CYCLE))}
      >
        Typ: {filter.type ? `${typeGlyph(filter.type)} ${filter.type}` : '—'}
      </button>
      <button
        type="button"
        data-testid="eventBrowse.filter.severity"
        aria-label={`Filter Severity: ${filter.severity ?? 'alle'}`}
        onClick={() => onCycle('severity', cycleAxis(filter.severity, SEVERITIES))}
      >
        Severity: {filter.severity ? severityLabel(filter.severity) : '—'}
      </button>
      {/* timeWindow: a present parity marker (the since/until axes exist in the query but have no picker yet, mirroring
          :app:shared) — shown so the axis is discoverable, deliberately non-interactive (no fabricated control). */}
      <span className="event-browse-time-window" data-testid="eventBrowse.filter.timeWindow">
        Zeitfenster: —
      </span>

      {/* subset-cue: a filtered view is NEVER read as "nothing happened" (spec §3, tooth 3). */}
      {isFilterActive(filter) && (
        <span className="event-browse-filter-active" role="status" data-testid="eventBrowse.filterActive">
          Filter aktiv – Teilmenge
        </span>
      )}
      {isCrossProjectView(filter) && (
        <span className="event-browse-cross-project" role="status" data-testid="eventBrowse.crossProjectView">
          Projektübergreifende Ansicht
        </span>
      )}
      {drilldown !== null && (
        <button
          type="button"
          className="event-browse-drilldown-header"
          data-testid="eventBrowse.drilldown.header"
          onClick={onClearDrilldown}
          aria-label="Korrelation aufheben"
        >
          Korreliert nach {drilldown.kind === 'run' ? `Lauf ${drilldown.correlationId}` : `Session ${drilldown.sessionId}`} · ✕
        </button>
      )}
    </div>
  )
}

function DetailPane({
  event,
  onBack,
  onDrill,
}: {
  event: EventSurrogate
  onBack: () => void
  onDrill: (axis: DrilldownAxis) => void
}) {
  const run = runDrilldown(event)
  const session = sessionDrilldown(event)
  const compact = compactDoneSummary(event)
  const resume = resumeOutcomeSummary(event)
  return (
    <div className="event-browse-detail" role="region" aria-label="Ereignis-Detail" data-testid="eventBrowse.detail">
      <button type="button" className="event-browse-back" data-testid="eventBrowse.back" onClick={onBack} aria-label="Zurück">
        ‹ Zurück
      </button>
      <div className="event-browse-detail-head">
        <span className="event-type">
          <span className="event-type-text">{event.type}</span>
        </span>
        <span>· {severityLabel(event.severity)}</span>
        <span className="event-browse-detail-meta">
          {formatLocalHhMm(event.ts)} · seq {event.seq} · {event.agentId}
        </span>
      </div>
      {/* sourceTs = observed (informative), NEVER authoritative — ordering is seq (spec §5/tooth 6). */}
      {event.sourceTs != null && (
        <p className="event-browse-source-ts" data-testid="eventBrowse.detail.sourceTs">
          Beobachtet: {formatLocalHhMm(event.sourceTs)}
        </p>
      )}
      {/* CYP-326 §2.5: the typed X/N compact summary above the raw JSON — WARN amber on timeout/abort, neutral on a
          clean full run (never green). The count/"Timeout"/"Abgebrochen" text carries it; colour is never alone. */}
      {compact !== null && (
        <p
          className={`event-browse-summary${compact.warn ? ' event-browse-summary-warn' : ''}`}
          data-testid="eventBrowse.detail.compactSummary"
        >
          {compact.warn && <span aria-hidden="true">▲ </span>}
          {compact.text}
        </p>
      )}
      {/* CYP-356: the resume outcome — CONTEXT_LOST is WARN amber (unintended loss); resumed/fresh are neutral. */}
      {resume !== null && (
        <p
          className={`event-browse-summary${resume.warn ? ' event-browse-summary-warn' : ''}`}
          data-testid="eventBrowse.detail.resumeOutcome"
        >
          {resume.warn && <span aria-hidden="true">▲ </span>}
          {resume.text}
        </p>
      )}
      {/* content-free detail payload, raw/as-is — nothing fabricated (spec §5.5/tooth 8). */}
      <pre className="event-browse-json" data-testid="eventBrowse.detail.json">
        {event.detail === undefined || event.detail === null ? '—' : JSON.stringify(event.detail, null, 2)}
      </pre>
      <div className="event-browse-drill-actions">
        {/* two axes, each enabled ONLY when the field is present — never guessed, never conflated (tooth 1). */}
        <button
          type="button"
          data-testid="eventBrowse.detail.showRun"
          disabled={!canShowRun(event)}
          aria-disabled={!canShowRun(event)}
          onClick={() => run && onDrill(run)}
        >
          Ganzen Lauf zeigen
        </button>
        <button
          type="button"
          data-testid="eventBrowse.detail.showSession"
          disabled={!canShowSession(event)}
          aria-disabled={!canShowSession(event)}
          onClick={() => session && onDrill(session)}
        >
          Ganze Session zeigen
        </button>
      </div>
    </div>
  )
}
