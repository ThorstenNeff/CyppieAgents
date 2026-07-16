// @vitest-environment jsdom
// CYP-644 — render teeth for the handoff/context-lost banner. Container-scoped (no auto-cleanup in this repo).
import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import { HandoffBanner } from './HandoffBanner'
import type { AgentTerminalControlEvent } from '../types/generated/contract'

const ev = (state: AgentTerminalControlEvent['state'], extra: Partial<AgentTerminalControlEvent> = {}): AgentTerminalControlEvent => ({
  agentId: 'backend',
  state,
  ...extra,
})
const q = (c: HTMLElement, id: string) => c.querySelector(`[data-testid="${id}"]`)

describe('HandoffBanner (render)', () => {
  it('INTERACTIVE → a status banner naming the holder, WARN glyph present', () => {
    const { container } = render(<HandoffBanner agentId="backend" control={ev('INTERACTIVE', { heldBy: 'po', since: 1_700_000_000_000 })} />)
    const b = q(container, 'handoff-banner.backend.handoff')
    expect(b?.getAttribute('role')).toBe('status')
    expect(b?.textContent).toContain('@po')
    expect(b?.textContent).toContain('▲')
    expect(b?.getAttribute('aria-label')).toContain('@po')
  })

  it('INTERACTIVE with no holder → "?" placeholder (never blank)', () => {
    const { container } = render(<HandoffBanner agentId="backend" control={ev('INTERACTIVE')} />)
    expect(q(container, 'handoff-banner.backend.handoff')?.textContent).toContain('?')
  })

  it('CONTEXT_LOST → the context-lost banner', () => {
    const { container } = render(<HandoffBanner agentId="backend" control={ev('CONTEXT_LOST')} />)
    expect(q(container, 'handoff-banner.backend.contextLost')?.textContent).toContain('Kontext verloren')
  })

  it('MEDIATED / undefined → renders NOTHING (fail-closed)', () => {
    const { container: a } = render(<HandoffBanner agentId="backend" control={ev('MEDIATED')} />)
    expect(a.querySelector('.handoff-banner')).toBeNull()
    const { container: b } = render(<HandoffBanner agentId="backend" control={undefined} />)
    expect(b.querySelector('.handoff-banner')).toBeNull()
  })
})
