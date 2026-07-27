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
  it('INTERACTIVE → an assertive banner naming the holder, WARN glyph present (CYP-847)', () => {
    const { container } = render(<HandoffBanner agentId="backend" control={ev('INTERACTIVE', { heldBy: 'po', since: 1_700_000_000_000 })} />)
    const b = q(container, 'handoff-banner.backend.handoff')
    expect(b?.getAttribute('role')).toBe('alert')
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

// CYP-847 (a11y register parity, PL-adjudicated per UIUX2 spec 1878df41) — BOTH banners are Tier-1 live event-announces
// (unsolicited-critical) → role="alert" + explicit aria-live="assertive" (native parity; web-ts house pattern
// auth/LoginScreen.tsx:119). Only the announce REGISTER changes — glyph/copy/aria-label/amber are untouched.
describe('CYP-847 — HandoffBanner announce register: BOTH banners are assertive (Tier-1 live event)', () => {
  it('★ contextLost → role="alert" AND aria-live="assertive" (Tooth 1a — polite/status reds = the divergence bug back)', () => {
    const { container } = render(<HandoffBanner agentId="backend" control={ev('CONTEXT_LOST')} />)
    const b = q(container, 'handoff-banner.backend.contextLost')
    expect(b?.getAttribute('role')).toBe('alert')
    expect(b?.getAttribute('aria-live')).toBe('assertive')
  })

  it('★ handoff → role="alert" AND aria-live="assertive" (Tooth 1b — both banners, not only contextLost)', () => {
    const { container } = render(<HandoffBanner agentId="backend" control={ev('INTERACTIVE', { heldBy: 'po', since: 1_700_000_000_000 })} />)
    const b = q(container, 'handoff-banner.backend.handoff')
    expect(b?.getAttribute('role')).toBe('alert')
    expect(b?.getAttribute('aria-live')).toBe('assertive')
  })

  it('★ ONLY the register changed — glyph ▲, copy, and handoff aria-label are untouched (Tooth 3 — no scope-creep)', () => {
    // Mutation: a copy/glyph/aria-label edit alongside the register change → reds. The register fix must be register-only.
    const cl = render(<HandoffBanner agentId="backend" control={ev('CONTEXT_LOST')} />)
    const clBanner = q(cl.container, 'handoff-banner.backend.contextLost')
    expect(clBanner?.textContent).toContain('▲')
    expect(clBanner?.textContent).toContain('Kontext verloren — ohne vorherige Historie zurückgekehrt.')
    expect(clBanner?.querySelector('.handoff-glyph')?.getAttribute('aria-hidden')).toBe('true') // glyph decorative, not announced

    const ho = render(<HandoffBanner agentId="backend" control={ev('INTERACTIVE', { heldBy: 'po', since: 1_700_000_000_000 })} />)
    const hoBanner = q(ho.container, 'handoff-banner.backend.handoff')
    expect(hoBanner?.textContent).toContain('▲')
    expect(hoBanner?.textContent).toContain('Terminal übergeben an @po')
    // aria-label copy unchanged — assert the stable fragments (the timestamp itself is locale/tz-dependent, not copy).
    const label = hoBanner?.getAttribute('aria-label') ?? ''
    expect(label).toContain('Terminal an @po übergeben, seit ')
    expect(label).toContain(' — interaktive Sitzung aktiv')
    expect(hoBanner?.querySelector('.handoff-glyph')?.getAttribute('aria-hidden')).toBe('true')
  })
})
