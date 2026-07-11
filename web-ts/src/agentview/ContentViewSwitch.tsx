// CYP-406 (W8) — the load-bearing retention rule (spec §W8.2): BOTH views stay MOUNTED; switching toggles only
// VISIBILITY (the `hidden` attribute), never unmounts. An unmounted xterm loses its scrollback and the composer
// loses its buffer; keeping both mounted preserves React state + the terminal history across every toggle.
// `term.dispose()` happens only at window close (XtermView's unmount), never here.
import type { SelectedView } from './terminalModeSelection'

export function ContentViewSwitch({
  active,
  orchestration,
  shell,
}: {
  active: SelectedView
  orchestration: React.ReactNode
  shell: React.ReactNode
}) {
  return (
    <div className="content-view-switch">
      <div hidden={active !== 'orchestration'} data-testid="view-orchestration">
        {orchestration}
      </div>
      <div hidden={active !== 'shell'} data-testid="view-shell">
        {shell}
      </div>
    </div>
  )
}
