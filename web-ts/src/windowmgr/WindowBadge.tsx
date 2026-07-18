// CYP-646 (Epic CYP-640) — renders the Count / Severity window badge at the end of the comm / event-log title bar.
// Parity with the CMP WindowBadge (CYP-55): a neutral number pill for Count, a severity-glyph pill for Severity.
// Form carries meaning (number vs glyph), so the variants are distinguishable without colour (WCAG 1.4.1); each
// carries an a11y label naming the window + meaning.
import type { WindowBadge as WindowBadgeModel } from './windowBadgeModel'
import { formatBadgeCount } from './windowBadgeModel'
import { severityGlyph, severityLabel } from '../eventlog/eventLog'

export function WindowBadge({ title, badge }: { title: string; badge: WindowBadgeModel }) {
  if (badge.kind === 'count') {
    return (
      <span
        className="window-badge window-badge-count"
        data-testid="window-badge.count"
        aria-label={`${title}: ${badge.count} ungelesen`}
      >
        {formatBadgeCount(badge.count)}
      </span>
    )
  }
  // Severity — the glyph (shape) carries the level; the a11y label spells it. Toned via the shared event-sev class.
  return (
    <span
      className={`window-badge window-badge-severity event-sev-${badge.severity}`}
      data-testid="window-badge.severity"
      aria-label={`${title}: Schweregrad ${severityLabel(badge.severity)}`}
    >
      <span className="event-sev-glyph" aria-hidden="true">
        {severityGlyph(badge.severity)}
      </span>
    </span>
  )
}
