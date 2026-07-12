// CYP-432 (P2-c) / CYP-448 (P2-c.2) — the event-log live-tail window: the /ws/events stream as rows. Three colour
// axes stay separate (event-log-tokens.json / spec §6): severity (glyph + colour + label), type (group glyph +
// monospace enum text, NO hue), identity (agentId text). Colour is never the sole signal — severity rides the glyph
// + the readable label (in onSurface), the coloured glyph is decorative reinforcement. A seq GAP is an explicit
// amber row, never a silent skip; the bounded-ring trim is disclosed at the head (`event_tail_trimmed`) — "no
// silent caps" (spec §5.2). Unknown types show their raw string with the ⓘ group glyph (never swallowed, CYP-37).
// XSS: all fields are React text children (escaped), no innerHTML.
//
// CYP-448: PAUSE freezes the visible tail (App passes the frozen `events` + `bufferedCount`); while paused the
// `liveIndicator` is ABSENT and `pausedIndicator` is present — a frozen view is never shown as live (spec §5.6).
import { eventRows, type EventLogState } from './eventLog'
import { EventRow } from './EventRow'
import { useAutoscrollPin } from '../agentview/useAutoscrollPin'

export interface EventLogViewProps {
  /** the VISIBLE tail (frozen at the pause point when paused, else the full ring). */
  events: EventLogState['events']
  caughtUp: boolean
  /** CYP-448: oldest events dropped by the bounded ring — disclosed as a head marker, never silent. */
  trimmed?: number
  /** CYP-448: the tail is frozen. `liveIndicator` is suppressed; `pausedIndicator` shown. */
  paused?: boolean
  /** CYP-448: events buffered since pause (arrived beyond the frozen tip). */
  bufferedCount?: number
  onTogglePause?: () => void
}

export function EventLogView({
  events,
  caughtUp,
  trimmed = 0,
  paused = false,
  bufferedCount = 0,
  onTogglePause,
}: EventLogViewProps) {
  const rows = eventRows(events)
  // pin key includes pause so a frozen view stops autoscrolling to a tip it isn't showing.
  const tail = events.length === 0 ? `p${paused}` : `${events.length}|${events[events.length - 1].id}|p${paused}`
  const { ref, onScroll } = useAutoscrollPin(tail)
  // "Live" ONLY when caught up AND not paused (spec §5.6: a frozen view is never live).
  const live = caughtUp && !paused

  return (
    <div className="event-log" data-testid="event-log">
      <div className="event-log-toolbar">
        <div
          className={`event-log-status ${live ? 'live' : paused ? 'paused' : 'replaying'}`}
          role="status"
          aria-live="polite"
          data-testid="event-log-status"
        >
          {live ? (
            <span data-testid="event-log-live">● Live</span>
          ) : paused ? (
            <span data-testid="event-log-paused">⏸ Pausiert</span>
          ) : (
            'Verlauf lädt…'
          )}
          {paused && bufferedCount > 0 && (
            <span className="event-log-buffered" data-testid="event-log-buffered">
              {' '}
              {bufferedCount} neue (pausiert)
            </span>
          )}
        </div>
        {onTogglePause && (
          <button
            type="button"
            className="event-log-pause"
            onClick={onTogglePause}
            data-testid="event-log-pause"
            aria-pressed={paused}
            title={paused ? 'Fortsetzen' : 'Pausieren'}
          >
            {paused ? '▶' : '⏸'}
          </button>
        )}
      </div>

      {trimmed > 0 && (
        <p className="event-log-trimmed" role="status" data-testid="event-log-trimmed">
          ⤒ {trimmed} ältere {trimmed === 1 ? 'Ereignis' : 'Ereignisse'} verworfen (Puffer voll)
        </p>
      )}

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
                <EventRow key={row.event.id} event={row.event} testid={`event.row.${row.event.id}`} />
              ),
            )}
          </ol>
        )}
      </div>
    </div>
  )
}
