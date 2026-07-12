// @vitest-environment jsdom
import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup, fireEvent } from '@testing-library/react'
import { FidelityBadge } from './FidelityBadge'
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

afterEach(cleanup)

describe('FidelityBadge (CYP-488)', () => {
  it('full fidelity → NO badge (fail-closed by absence, no false "all green")', () => {
    const { queryByTestId } = render(<FidelityBadge agentId="be" capabilities={caps()} connectorKind="stream_json" />)
    expect(queryByTestId('connector.be.fidelityBadge')).toBeNull()
  })

  it('degraded → badge present ("Eingeschränkt"); opens the panel with per-dimension status chips (glyph + label)', () => {
    const { getByTestId, queryByTestId } = render(
      <FidelityBadge agentId="be" capabilities={caps({ rateLimitSignal: 'unavailable', toolGranularity: 'limited' })} connectorKind="mcp" />,
    )
    const badge = getByTestId('connector.be.fidelityBadge')
    expect(badge.textContent).toContain('Eingeschränkt')
    expect(queryByTestId('connector.be.capabilityPanel')).toBeNull() // closed until clicked
    fireEvent.click(badge)
    expect(getByTestId('connector.be.capabilityPanel')).toBeTruthy()
    expect(getByTestId('connector.be.activeConnector').textContent).toContain('MCP') // identity ≠ fidelity
    const chip = getByTestId('connector.be.capability.rateLimitSignal.status')
    expect(chip.getAttribute('data-status')).toBe('unavailable')
    expect(chip.textContent).toContain('○') // glyph
    expect(chip.textContent).toContain('Nicht verfügbar') // + label — colour never alone
  })

  it('capabilities NULL (not reported) → badge present ("noch nicht gemeldet"), panel shows not-reported, never "full"', () => {
    const { getByTestId } = render(<FidelityBadge agentId="be" capabilities={null} connectorKind={null} />)
    const badge = getByTestId('connector.be.fidelityBadge')
    expect(badge.textContent).toContain('noch nicht gemeldet')
    fireEvent.click(badge)
    expect(getByTestId('connector.be.notReported')).toBeTruthy()
  })

  it('the observed badge uses the agentId scope (never the advisory "preview" scope — registers not mixed)', () => {
    const { getByTestId, queryByTestId } = render(<FidelityBadge agentId="be" capabilities={caps({ coordination: 'limited' })} connectorKind="mcp" />)
    fireEvent.click(getByTestId('connector.be.fidelityBadge'))
    expect(getByTestId('connector.be.capability.coordination.status')).toBeTruthy() // agentId scope (observed)
    expect(queryByTestId('connector.preview.capability.coordination.status')).toBeNull() // NOT the advisory scope
  })
})
