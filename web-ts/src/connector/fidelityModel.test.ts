import { describe, it, expect } from 'vitest'
import { isDegraded, badgePresent, capStatusGlyph, activeConnectorLabel } from './fidelityModel'
import type { Capabilities } from '../types/generated/contract'

const caps = (over: Partial<Capabilities> = {}): Capabilities => ({
  structuredUsage: 'available',
  toolGranularity: 'available',
  reliableResult: 'available',
  rateLimitSignal: 'available',
  coordination: 'available',
  kind: 'stream_json',
  ...over,
})

describe('fidelity model (CYP-488 — observed, fail-closed by absence)', () => {
  it('isDegraded: false when all available, true if any dimension is not available', () => {
    expect(isDegraded(caps())).toBe(false)
    expect(isDegraded(caps({ rateLimitSignal: 'unavailable' }))).toBe(true)
    expect(isDegraded(caps({ toolGranularity: 'limited' }))).toBe(true)
  })

  it('badgePresent: NULL/undefined → present (not reported, never "full"); full → absent; degraded → present', () => {
    expect(badgePresent(null)).toBe(true) // fail-closed: not reported ≠ full
    expect(badgePresent(undefined)).toBe(true)
    expect(badgePresent(caps())).toBe(false) // full fidelity → NO badge (no false "all green")
    expect(badgePresent(caps({ coordination: 'limited' }))).toBe(true)
  })

  it('capStatusGlyph: distinct glyph per status (colour never alone)', () => {
    expect([capStatusGlyph('available'), capStatusGlyph('limited'), capStatusGlyph('unavailable')]).toEqual(['✓', '!', '○'])
  })

  it('activeConnectorLabel: identity ≠ fidelity; unknown → —', () => {
    expect(activeConnectorLabel('stream_json')).toBe('Stream-JSON')
    expect(activeConnectorLabel('mcp')).toBe('MCP')
    expect(activeConnectorLabel(null)).toBe('—')
  })
})
