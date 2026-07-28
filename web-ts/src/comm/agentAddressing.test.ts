import { describe, it, expect } from 'vitest'
import { resolveAgentChannel } from './agentAddressing'
import type { Channel } from '../types/generated/contract'

const ch = (id: string, kind: Channel['kind'], members: string[]): Channel => ({ id, name: id, kind, members })

describe('CYP-876 — resolveAgentChannel (fail-closed, render ≠ authority)', () => {
  const spokeFE = ch('po-frontend', 'DIRECT', ['po', 'frontend'])
  const spokeBE = ch('po-backend', 'DIRECT', ['po', 'backend'])
  const group = ch('team', 'GROUP', ['po', 'frontend', 'backend'])
  const hub = ch('hub', 'HUB', ['po', 'frontend', 'backend'])

  it('★ exactly one DIRECT spoke → resolved to that channel', () => {
    expect(resolveAgentChannel('frontend', [spokeFE, spokeBE, group, hub])).toEqual({ kind: 'resolved', channelId: 'po-frontend' })
  })

  it('★ no DIRECT spoke for the target → UNREACHABLE, never a fabricated channel', () => {
    // A GROUP/HUB membership is NOT a DM spoke. MUT: fall back to a GROUP/HUB (fabricate a DM route) → reds.
    expect(resolveAgentChannel('frontend', [group, hub])).toEqual({ kind: 'unreachable' })
    expect(resolveAgentChannel('nobody', [spokeFE, spokeBE])).toEqual({ kind: 'unreachable' })
  })

  it('★ more than one DIRECT spoke with the target → AMBIGUOUS (null+flag), NEVER silent-first', () => {
    // MUT: return resolved with spokes[0] (silent-first pick) → reds. The ambiguity is surfaced, not guessed away.
    const dupA = ch('po-frontend', 'DIRECT', ['po', 'frontend'])
    const dupB = ch('po-frontend-2', 'DIRECT', ['po', 'frontend'])
    const r = resolveAgentChannel('frontend', [dupA, dupB])
    expect(r.kind).toBe('ambiguous')
    if (r.kind === 'ambiguous') expect([...r.channelIds]).toEqual(['po-frontend', 'po-frontend-2'])
  })

  it('only DIRECT counts — a GROUP/HUB with the member is not a spoke', () => {
    expect(resolveAgentChannel('frontend', [group]).kind).toBe('unreachable')
    expect(resolveAgentChannel('frontend', [hub]).kind).toBe('unreachable')
  })
})
