// CYP-642 (P2, Epic CYP-640) — the hub-capacity readout pill in the workspace bar. Parity with CMP CapacityReadout
// (CYP-417). Absent when there is no capacity data (null ≠ "0/0"); neutral for "N aktiv" and for headroom; WARN-amber
// only when full. Colour is never the sole signal (WCAG 1.4.1): the count + label carry it, the aria-label spells
// the meaning; the `/M` a11y copy says "geschätzt" (estimate, not an SLA).
import type { Capacity } from '../types/generated/contract'
import { capacityReadout } from './capacityModel'

export function CapacityPill({ capacity }: { capacity: Capacity | null | undefined }) {
  const r = capacityReadout(capacity)
  if (r.kind === 'absent') return null // no data → render nothing (never a placeholder / "0/0")

  const full = r.kind === 'full'
  const text = r.kind === 'nomax' ? `${r.current} aktiv` : `${r.current}/${r.max}`
  const aria =
    r.kind === 'nomax'
      ? `Hub-Kapazität: ${r.current} aktiv`
      : `Hub-Kapazität: ${r.current} von geschätzt ${r.max}${full ? ' — voll' : ''}`

  return (
    <span
      className={`capacity-pill${full ? ' full' : ''}`}
      data-testid="capacity-pill"
      role="status"
      aria-label={aria}
    >
      <span className="capacity-count">{text}</span>
      {full && (
        <span className="capacity-full" data-testid="capacity-pill.full">
          voll
        </span>
      )}
    </span>
  )
}
