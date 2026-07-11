// CYP-406 (W8) — the NON-OPTIMISTIC view selection from the backend terminal-control state (/ws/terminal-state,
// AgentTerminalControlEvent). Spec §0.2/§W8.1: "Shell" is selected ONLY once the backend confirms INTERACTIVE;
// during HANDING_OVER the live selection stays "orchestration" + pending, symmetric for hand-back. Pure & tested.
import type { AgentTerminalControlEvent } from '../types/generated/contract'

export type TerminalControlState = AgentTerminalControlEvent['state']
export type SelectedView = 'orchestration' | 'shell'

export interface ModeSelection {
  /** the view the UI shows as selected NOW (server-confirmed, never optimistic). */
  selected: SelectedView
  /** a hand-off is in flight → segment shows a spinner (aria-busy), live selection unchanged. */
  pending: boolean
  /** the interactive session lost context (CYP-333) → the receded/dimmed-history marker applies. */
  contextLost: boolean
}

export function terminalModeSelection(state: TerminalControlState): ModeSelection {
  switch (state) {
    case 'MEDIATED':
      return { selected: 'orchestration', pending: false, contextLost: false }
    case 'HANDING_OVER':
      return { selected: 'orchestration', pending: true, contextLost: false } // becoming shell; not selected yet
    case 'INTERACTIVE':
      return { selected: 'shell', pending: false, contextLost: false }
    case 'HANDING_BACK':
      return { selected: 'shell', pending: true, contextLost: false } // still shell until MEDIATED confirms
    case 'CONTEXT_LOST':
      return { selected: 'orchestration', pending: false, contextLost: true }
  }
}
