// @vitest-environment jsdom
// CYP-810 G-a6 — HandoffBanner contextLost glyph is the WARN ▲, never the ERROR ⚠. The existing render test pins ▲ only
// on the INTERACTIVE/handoff branch; the CONTEXT_LOST branch asserts only its text ("Kontext verloren"), so the glyph
// literal in that branch (▲) was unpinned — ▲→⚠ would show a context-loss caution as a "broken" ERROR and survive.
import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { HandoffBanner } from './HandoffBanner'
import type { AgentTerminalControlEvent } from '../types/generated/contract'

afterEach(cleanup)
const ev = (state: AgentTerminalControlEvent['state']): AgentTerminalControlEvent => ({ agentId: 'backend', state })

describe('CYP-810 G-a6 — HandoffBanner contextLost glyph is WARN ▲, never ERROR ⚠ (caution ≠ broken)', () => {
  it('★ the contextLost branch renders ▲ (WARN), not ⚠ (ERROR) — mutation ▲→⚠ REDs', () => {
    const { container } = render(<HandoffBanner agentId="backend" control={ev('CONTEXT_LOST')} />)
    const glyph = container.querySelector('.handoff-glyph')
    expect(glyph?.textContent?.trim()).toBe('▲')
    expect(glyph?.textContent).not.toContain('⚠')
  })
})
