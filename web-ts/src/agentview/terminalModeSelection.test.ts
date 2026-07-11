import { describe, it, expect } from 'vitest'
import { terminalModeSelection } from './terminalModeSelection'

describe('terminalModeSelection (CYP-406 non-optimistic view selection)', () => {
  it('MEDIATED → orchestration, settled', () => {
    expect(terminalModeSelection('MEDIATED')).toEqual({ selected: 'orchestration', pending: false, contextLost: false })
  })
  it('INTERACTIVE → shell selected', () => {
    expect(terminalModeSelection('INTERACTIVE')).toEqual({ selected: 'shell', pending: false, contextLost: false })
  })
  it('HANDING_OVER stays orchestration + pending (shell not selected until the backend confirms)', () => {
    expect(terminalModeSelection('HANDING_OVER')).toEqual({ selected: 'orchestration', pending: true, contextLost: false })
  })
  it('HANDING_BACK stays shell + pending (still shell until MEDIATED confirms)', () => {
    expect(terminalModeSelection('HANDING_BACK')).toEqual({ selected: 'shell', pending: true, contextLost: false })
  })
  it('CONTEXT_LOST → orchestration + the contextLost marker', () => {
    expect(terminalModeSelection('CONTEXT_LOST')).toEqual({ selected: 'orchestration', pending: false, contextLost: true })
  })
})
