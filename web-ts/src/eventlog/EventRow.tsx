// CYP-452 (P2-c.2) — the SHARED event row (mirror Compose EventRowUi). Both the live-tail (EventLogView) and Browse
// render THIS one row, so the three non-colliding axes never diverge (spec §7): severity (glyph + hue + label, colour
// never the sole signal — WCAG 1.4.1), type (group glyph + monospace wire text, NO hue; UNKNOWN keeps its raw string,
// CYP-37), identity (agentId text). The severity/glyph/label helpers are the single source in eventLog.ts.
//
// The OUTER row test-id is caller-supplied (tail = `event.row.<id>`, Browse = `eventBrowse.row.N`); the inner cells are
// identical. A Browse row is selectable (onSelect → role=button + keyboard); a tail row is static. XSS: every field is
// a React text child (escaped), no innerHTML.
import { severityGlyph, severityLabel, typeGlyph } from './eventLog'
import { formatLocalHhMm } from '../agentview/transcriptTime'
import type { EventSurrogate } from '../types/generated/contract'

/** ~120-char content-free summary of the detail payload (the raw JSON is shown in the Browse detail pane, never here). */
export function detailSummary(detail: unknown): string {
  if (detail === undefined || detail === null) return ''
  const s = typeof detail === 'string' ? detail : JSON.stringify(detail)
  return s.length > 120 ? `${s.slice(0, 117)}…` : s
}

export interface EventRowProps {
  event: EventSurrogate
  /** the outer <li> test-id — the ONE thing that differs between tail and Browse. */
  testid: string
  /** Browse rows are selectable (opens the detail pane); tail rows omit this and stay static. */
  onSelect?: () => void
  /** Browse cross-project view tags each row with its project identity (text, never colour alone — spec §3). */
  showProject?: boolean
}

export function EventRow({ event, testid, onSelect, showProject = false }: EventRowProps) {
  const selectable = onSelect !== undefined
  const summary = detailSummary(event.detail)
  return (
    <li
      className={`event-row${selectable ? ' event-row-selectable' : ''}`}
      data-testid={testid}
      {...(selectable
        ? {
            role: 'button',
            tabIndex: 0,
            onClick: onSelect,
            onKeyDown: (e: React.KeyboardEvent) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault()
                onSelect()
              }
            },
          }
        : {})}
    >
      <time>{formatLocalHhMm(event.ts)}</time>
      <span
        className={`event-sev event-sev-${event.severity}`}
        data-severity={event.severity}
        title={severityLabel(event.severity)}
      >
        <span className="event-sev-glyph" aria-hidden="true">
          {severityGlyph(event.severity)}
        </span>
        <span className="event-sev-label">{severityLabel(event.severity)}</span>
      </span>
      <span className="event-type" data-testid={`event.type.${event.id}`}>
        <span className="event-type-glyph" aria-hidden="true">
          {typeGlyph(event.type)}
        </span>
        <span className="event-type-text">{event.type}</span>
      </span>
      <span className="event-agent">{event.agentId}</span>
      {showProject && event.projectId && (
        <span className="event-project" data-testid={`${testid}.project`}>
          {event.projectId}
        </span>
      )}
      {summary !== '' && <span className="event-detail">{summary}</span>}
    </li>
  )
}
