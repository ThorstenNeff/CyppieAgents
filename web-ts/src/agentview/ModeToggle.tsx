// CYP-406 (W8) — the [ Orchestrierung | Shell ] segmented control (radiogroup), spec §W8.1. Non-optimistic: the
// checked segment follows the SERVER-confirmed selection (terminalModeSelection), never the click; a hand-off in
// flight shows aria-busy and the live selection is unchanged. Non-operator: read-only (aria-disabled) + the
// operator-only note (CYP-317 "no fake switch") — it shows the live mode, never drives it.
import { terminalModeSelection, type TerminalControlState, type SelectedView } from './terminalModeSelection'

export function ModeToggle({
  state,
  operator,
  onRequestMode,
}: {
  state: TerminalControlState
  operator: boolean
  onRequestMode: (mode: SelectedView) => void
}) {
  const sel = terminalModeSelection(state)
  const request = (mode: SelectedView) => {
    if (operator && !sel.pending) onRequestMode(mode)
  }
  return (
    <div role="radiogroup" aria-label="Ansicht" aria-busy={sel.pending} className="mode-toggle" data-testid="mode-toggle">
      <button
        type="button"
        role="radio"
        aria-checked={sel.selected === 'orchestration'}
        aria-disabled={!operator}
        className="mode-segment"
        data-testid="mode-orchestration"
        onClick={() => request('orchestration')}
      >
        Orchestrierung
      </button>
      <button
        type="button"
        role="radio"
        aria-checked={sel.selected === 'shell'}
        aria-disabled={!operator}
        className="mode-segment"
        data-testid="mode-shell"
        onClick={() => request('shell')}
      >
        Shell
      </button>
      {sel.pending && (
        <span className="mode-pending" aria-hidden="true" data-testid="mode-pending">
          …
        </span>
      )}
      {!operator && (
        <p className="operator-only" data-testid="mode-operator-only">
          Nur Operatoren können die Ansicht umschalten.
        </p>
      )}
    </div>
  )
}
