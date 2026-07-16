// CYP-645 (P5, Epic CYP-640) — the personal, UNGATED stepper for the ONE global composer input-history size N
// (the ↑/↓ recall depth). Parity with CMP ComposerHistorySizeStepper (CYP-387): `[−] N [+]`, range 0..MAX (0 = off),
// rides the workspace bar beside the theme toggle (same "not operator-gated, not project-scoped" family). It only
// DRIVES N via onChange; App owns the clamp + durable persistence (saveHistorySize). The − / + are disabled at the
// bounds so the control never emits out-of-range (App clamps as a backstop anyway).
import { MAX_HISTORY_SIZE } from './inputHistory'

const MIN_HISTORY_SIZE = 0 // 0 = recall off (clampHistorySize floors at 0)

export function ComposerHistoryStepper({ size, onChange }: { size: number; onChange: (n: number) => void }) {
  const atMin = size <= MIN_HISTORY_SIZE
  const atMax = size >= MAX_HISTORY_SIZE
  // 0 is an honest, announced value (recall off) — never a dead control; the label spells it.
  const valueLabel = size === 0 ? '0 (aus)' : String(size)
  return (
    <span
      className="composer-history-stepper"
      data-testid="composer-history-stepper"
      role="group"
      aria-label={`Eingabe-Verlauf (Recall-Tiefe): ${size}${size === 0 ? ' — aus' : ''}`}
    >
      <span className="chs-label">Verlauf</span>
      <button
        type="button"
        className="chs-btn"
        data-testid="composer-history-stepper.dec"
        aria-label="Verlauf verkleinern"
        disabled={atMin}
        onClick={() => onChange(size - 1)}
      >
        −
      </button>
      <span className="chs-value" data-testid="composer-history-stepper.value" aria-hidden="true">
        {valueLabel}
      </span>
      <button
        type="button"
        className="chs-btn"
        data-testid="composer-history-stepper.inc"
        aria-label="Verlauf vergrößern"
        disabled={atMax}
        onClick={() => onChange(size + 1)}
      >
        +
      </button>
    </span>
  )
}
