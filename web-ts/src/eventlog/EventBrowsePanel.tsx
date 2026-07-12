// CYP-452 (P2-c.2) — the Event-Log Browse panel: offline inspection over the same operator-gated, mount-gated bodies
// as the live-tail (CYP-432 leak boundary — App mounts this ONLY for an operator; omission, not present-but-disabled).
// Distinct from the tail: seq-paged REST + server-side filter + detail + two-axis drilldown. Shares the ONE EventRow +
// severity/glyph source (spec §7). The load-bearing honesty:
//   - server-side query per filter/drilldown tap (never a client post-filter over loaded data — spec §3/tooth 2);
//   - filterActive subset-cue + cross-project indicator (absence ≠ all-clear — spec §3/tooth 3);
//   - error BEATS empty: a failed first-page → error+retry, NOT the "no events" state (CYP-288/tooth 4);
//   - no invented correlation: showRun/showSession enabled ONLY when the event carries the field, two axes never
//     conflated (spec §5/§6/tooth 1);
//   - sourceTs = "observed" (informative), ordering is seq (tooth 6); content-free detail JSON as-is (tooth 8).
// Deferred (flagged): typed compact/resume WARN-amber detail summaries + full single-pane back-nav — fast-follow.
import { useEffect, useMemo, useRef, useState } from 'react'
import type { EventPage, EventSurrogate } from '../types/generated/contract'
import { RestError } from '../net/rest'
import { EventRow } from './EventRow'
import { severityLabel, type Severity } from './eventLog'
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
  type EventFilter,
  type DrilldownAxis,
} from './eventBrowse'

const PAGE_LIMIT = 100
const SEVERITIES: readonly Severity[] = ['debug', 'info', 'warn', 'error']

export interface EventBrowsePanelProps {
  getEvents: (filter: EventFilter, afterSeq: number | null, limit: number) => Promise<EventPage>
  /** agent-chip options (the roster ids). */
  agentIds: readonly string[]
}

export function EventBrowsePanel({ getEvents, agentIds }: EventBrowsePanelProps) {
  const [filter, setFilter] = useState<EventFilter>(EMPTY_FILTER)
  const [drilldown, setDrilldown] = useState<DrilldownAxis | null>(null)
  const [events, setEvents] = useState<readonly EventSurrogate[]>([])
  const [nextAfterSeq, setNextAfterSeq] = useState<number | null>(null)
  const [hasMore, setHasMore] = useState(false)
  const [loading, setLoading] = useState(false)
  const [firstPageError, setFirstPageError] = useState(false)
  const [accessRevoked, setAccessRevoked] = useState(false)
  const [selectedId, setSelectedId] = useState<string | null>(null)

  // The effective query = the drilldown's single-axis filter when drilled in, else the filter bar.
  const effectiveFilter = useMemo(() => (drilldown ? drilldownFilter(drilldown) : filter), [drilldown, filter])

  // Load the FIRST page whenever the effective query changes (a filter/drilldown tap → a new server query).
  const reqSeq = useRef(0)
  useEffect(() => {
    const req = ++reqSeq.current
    setLoading(true)
    setFirstPageError(false)
    getEvents(effectiveFilter, null, PAGE_LIMIT)
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
        // Runtime access revoke (403) → fail-closed: drop everything, lock the surface (CYP-432 leak parity).
        if (e instanceof RestError && e.status === 403) {
          setEvents([])
          setAccessRevoked(true)
        } else {
          setFirstPageError(true) // error BEATS empty (tooth 4)
        }
        setLoading(false)
      })
  }, [effectiveFilter, getEvents])

  const loadMore = () => {
    if (nextAfterSeq === null || loading) return
    setLoading(true)
    const req = reqSeq.current // loadMore belongs to the current query
    getEvents(effectiveFilter, nextAfterSeq, PAGE_LIMIT)
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

  return (
    <div className="event-browse" data-testid="eventBrowse">
      <FilterBar
        filter={filter}
        agentIds={agentIds}
        drilldown={drilldown}
        onCycle={setAxis}
        onClearDrilldown={() => setDrilldown(null)}
      />

      <div className="event-browse-body">
        <div className="event-browse-master">
          {firstPageError ? (
            <div className="event-browse-error" role="alert" data-testid="eventBrowse.error">
              <span>Laden fehlgeschlagen.</span>
              <button
                type="button"
                data-testid="eventBrowse.error.retry"
                onClick={() => setFilter((f) => ({ ...f }))} // re-run the current query (new object → effect)
              >
                Erneut versuchen
              </button>
            </div>
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

        {selected !== null && (
          <DetailPane event={selected} onBack={() => setSelectedId(null)} onDrill={setDrilldown} />
        )}
      </div>
    </div>
  )
}

function FilterBar({
  filter,
  agentIds,
  drilldown,
  onCycle,
  onClearDrilldown,
}: {
  filter: EventFilter
  agentIds: readonly string[]
  drilldown: DrilldownAxis | null
  onCycle: <K extends keyof EventFilter>(key: K, value: EventFilter[K]) => void
  onClearDrilldown: () => void
}) {
  return (
    <div className="event-browse-filterbar" data-testid="eventBrowse.filterBar">
      <button
        type="button"
        data-testid="eventBrowse.filter.agent"
        aria-label={`Filter Agent: ${filter.agentId ?? 'alle'}`}
        onClick={() => onCycle('agentId', cycleAxis(filter.agentId, agentIds))}
      >
        Agent: {filter.agentId ?? '—'}
      </button>
      <button
        type="button"
        data-testid="eventBrowse.filter.severity"
        aria-label={`Filter Severity: ${filter.severity ?? 'alle'}`}
        onClick={() => onCycle('severity', cycleAxis(filter.severity, SEVERITIES))}
      >
        Severity: {filter.severity ? severityLabel(filter.severity) : '—'}
      </button>

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
