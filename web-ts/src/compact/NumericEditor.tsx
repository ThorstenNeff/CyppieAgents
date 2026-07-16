// CYP-649 (P6, Epic CYP-640) — the shared numeric editor for the compact panel's threshold + 3 timing tunables.
// Parity with CMP ThresholdEditor/TimingEditor: a draft input (digit-filtered), a live preview of the formatted
// value, a Set button, and an inline range-error. NON-OPTIMISTIC: the draft mirrors the server `current` and
// re-syncs whenever `current` changes (a confirmed set / poll) — the value shown is never the un-confirmed draft.
// The Set button is disabled unless the draft is a valid, CHANGED value (and the caller is editable); the SERVER
// range-validates too (400), so this is a fail-closed convenience, not the guard.
import { useEffect, useState } from 'react'

export function NumericEditor({
  label,
  current,
  bounds,
  preview,
  editable,
  onSet,
  testId,
}: {
  label: string
  current: number
  bounds: { readonly min: number; readonly max: number }
  preview: (n: number) => string
  editable: boolean
  onSet: (n: number) => void
  testId: string
}) {
  const [draft, setDraft] = useState(String(current))
  // Mirror the server: when the confirmed value changes, reset the draft to it (never keep a stale local edit).
  useEffect(() => setDraft(String(current)), [current])

  const parsed = draft === '' ? Number.NaN : Number(draft)
  const valid = Number.isInteger(parsed) && parsed >= bounds.min && parsed <= bounds.max
  const changed = valid && parsed !== current

  return (
    <div className="compact-editor" data-testid={testId}>
      <label className="compact-editor-label" htmlFor={`${testId}-input`}>
        {label}
      </label>
      <input
        id={`${testId}-input`}
        className="compact-editor-input"
        data-testid={`${testId}.input`}
        inputMode="numeric"
        value={draft}
        disabled={!editable}
        onChange={(e) => setDraft(e.target.value.replace(/\D/g, '').slice(0, 7))}
      />
      {valid && (
        <span className="compact-editor-preview" data-testid={`${testId}.preview`}>
          = {preview(parsed)}
        </span>
      )}
      <button
        type="button"
        className="compact-editor-set"
        data-testid={`${testId}.set`}
        disabled={!editable || !changed}
        onClick={() => onSet(parsed)}
      >
        Setzen
      </button>
      {draft !== '' && !valid && (
        <p className="compact-editor-error" role="alert" data-testid={`${testId}.error`}>
          Wert außerhalb {bounds.min}–{bounds.max}
        </p>
      )}
    </div>
  )
}
