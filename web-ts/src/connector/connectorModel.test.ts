import { describe, it, expect } from 'vitest'
import { canConfirmOptIn, previewCapabilities, connectorKindLabel, statusLabel } from './connectorModel'
import type { ConnectorsView } from '../types/generated/contract'

const view: ConnectorsView = {
  connectors: [
    { kind: 'stream_json', capabilities: { structuredUsage: 'available', toolGranularity: 'available', reliableResult: 'available', rateLimitSignal: 'available', coordination: 'available', kind: 'stream_json' } },
    { kind: 'mcp', capabilities: { structuredUsage: 'limited', toolGranularity: 'limited', reliableResult: 'limited', rateLimitSignal: 'unavailable', coordination: 'limited', kind: 'mcp' } },
  ],
  default: 'stream_json',
}

describe('canConfirmOptIn (CYP-461 — fail-closed: need editable ∧ ack ∧ visible preview)', () => {
  it('all three required — any missing → disabled', () => {
    expect(canConfirmOptIn(true, true, true)).toBe(true)
    expect(canConfirmOptIn(false, true, true)).toBe(false) // not editable
    expect(canConfirmOptIn(true, false, true)).toBe(false) // not acknowledged
    expect(canConfirmOptIn(true, true, false)).toBe(false) // preview not loaded (can't ack a risk you can't see)
  })
})

describe('previewCapabilities (CYP-461 — advisory preview per kind from GET /api/connectors)', () => {
  it('extracts the mcp descriptor capabilities', () => {
    expect(previewCapabilities(view, 'mcp')?.rateLimitSignal).toBe('unavailable')
    expect(previewCapabilities(view, 'stream_json')?.structuredUsage).toBe('available')
  })
  it('null when the view is absent (fail-closed) or the kind is missing', () => {
    expect(previewCapabilities(null, 'mcp')).toBe(null)
    expect(previewCapabilities({ connectors: [] }, 'mcp')).toBe(null)
  })
})

describe('labels', () => {
  it('kind + status labels', () => {
    expect(connectorKindLabel('stream_json')).toContain('Stream-JSON')
    expect(connectorKindLabel('mcp')).toContain('MCP')
    expect([statusLabel('available'), statusLabel('limited'), statusLabel('unavailable')]).toEqual([
      'Verfügbar',
      'Eingeschränkt',
      'Nicht verfügbar',
    ])
  })
})
