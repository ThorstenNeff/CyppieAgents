// CYP-641 (P1, Epic CYP-640) — the per-window LIVE ACTIVITY badge, rendered at the end of an agent window's title
// bar. It surfaces the two feeds this story mounts (`/ws/busy-state` → busy marker, `/ws/token-usage` → context-
// token count) plus an honest-ERROR attention marker (run-state). Parity with the CMP title-bar indicator + the
// CYP-55 A1 attention marker.
//
// **Colour is never the sole carrier (WCAG 1.4.1):** every atom has a glyph/number AND a spoken `aria-label` naming
// the window + meaning; the tones are secondary. **Layout-stable:** the token count is compact-formatted so the
// title bar never grows with magnitude. The whole badge only mounts when [activity] is non-null (fail-closed).
import type { WindowActivity } from './activityBadge'
import { formatContextTokens } from './activityBadge'

export function WindowActivityBadge({ title, activity }: { title: string; activity: WindowActivity }) {
  return (
    <span className="window-activity" data-testid="window-activity">
      {activity.contextTokens !== null && (
        <span
          className="wa-tokens"
          data-testid="window-activity.tokens"
          aria-label={`${title}: Kontext ${activity.contextTokens.toLocaleString('de-DE')} Tokens`}
          title="Kontext-Tokens"
        >
          {formatContextTokens(activity.contextTokens)}
        </span>
      )}
      {activity.busy && (
        <span
          className="wa-busy"
          data-testid="window-activity.busy"
          role="img"
          aria-label={`${title}: arbeitet`}
          title="arbeitet"
        >
          {/* three dots = "working now"; a shape/text marker, not colour */}
          •••
        </span>
      )}
      {activity.attention && (
        <span
          className="wa-attention"
          data-testid="window-activity.attention"
          role="img"
          aria-label={`${title}: Agent-Fehler`}
          title="Agent-Fehler"
        >
          ⚠
        </span>
      )}
    </span>
  )
}
