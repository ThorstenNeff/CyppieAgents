// CYP-432 (P2-c) — the event-log window: the /ws/events stream as rows. Three colour axes stay separate
// (event-log-tokens.json): severity (glyph + colour + label), type (monospace enum text, NO hue), identity
// (agentId text). Colour is never the sole signal — severity rides the glyph + the readable label (in onSurface),
// the coloured glyph is decorative reinforcement. A seq GAP is an explicit amber row, never a silent skip
// ("no silent caps"). Unknown types show their raw string (never swallowed). XSS: all fields are React text
// children (escaped), no innerHTML.
//
// ⚠ Pending UIUX (spec 8b679eec): the per-TYPE group icons (the `▤`-family) are not finalized — this renders the
// type as monospace text only (which honestly carries it); the decorative group icon lands once UIUX signs it off.
import { eventRows, severityGlyph, severityLabel, type EventLogState } from './eventLog'
import { formatLocalHhMm } from '../agentview/transcriptTime'
import { useAutoscrollPin } from '../agentview/useAutoscrollPin'
import type { EventSurrogate } from '../types/generated/contract'

const detailSummary = (detail: unknown): string => {
  if (detail === undefined || detail === null) return ''
  const s = typeof detail === 'string' ? detail : JSON.stringify(detail)
  return s.length > 120 ? `${s.slice(0, 117)}…` : s
}

export interface EventLogViewProps {
  events: EventLogState['events']
  caughtUp: boolean
}

export function EventLogView({ events, caughtUp }: EventLogViewProps) {
  const rows = eventRows(events)
  const tail = events.length === 0 ? '' : `${events.length}|${events[events.length - 1].id}`
  const { ref, onScroll } = useAutoscrollPin(tail)

  return (
    <div className="event-log" data-testid="event-log">
      <div
        className={`event-log-status ${caughtUp ? 'live' : 'replaying'}`}
        role="status"
        aria-live="polite"
        data-testid="event-log-status"
      >
        {caughtUp ? 'Live' : 'Verlauf lädt…'}
      </div>

      <div className="event-log-rows transcript-scroll" ref={ref} onScroll={onScroll} data-testid="event-log-rows">
        {rows.length === 0 ? (
          <p className="event-log-empty" data-testid="event-log-empty">
            Noch keine Ereignisse.
          </p>
        ) : (
          <ol>
            {rows.map((row) =>
              row.kind === 'gap' ? (
                <li
                  key={`gap-${row.afterSeq}`}
                  className="event-gap"
                  data-testid={`event.gap.${row.afterSeq}`}
                  role="alert"
                >
                  ⚠ {row.count} {row.count === 1 ? 'Ereignis' : 'Ereignisse'} verworfen (Lücke im Protokoll)
                </li>
              ) : (
                <EventRow key={row.event.id} event={row.event} />
              ),
            )}
          </ol>
        )}
      </div>
    </div>
  )
}

function EventRow({ event }: { event: EventSurrogate }) {
  return (
    <li className="event-row" data-testid={`event.row.${event.id}`}>
      <time>{formatLocalHhMm(event.ts)}</time>
      <span className={`event-sev event-sev-${event.severity}`} data-severity={event.severity} title={severityLabel(event.severity)}>
        <span className="event-sev-glyph" aria-hidden="true">
          {severityGlyph(event.severity)}
        </span>
        <span className="event-sev-label">{severityLabel(event.severity)}</span>
      </span>
      <span className="event-type">{event.type}</span>
      <span className="event-agent">{event.agentId}</span>
      {detailSummary(event.detail) !== '' && <span className="event-detail">{detailSummary(event.detail)}</span>}
    </li>
  )
}
