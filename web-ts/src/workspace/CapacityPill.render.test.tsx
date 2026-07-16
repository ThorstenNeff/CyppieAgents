// @vitest-environment jsdom
// CYP-642 — render teeth for the capacity pill + overload banner. Queries are container-scoped (this repo registers
// no auto-cleanup between renders — matches the WindowFrame/WindowActivityBadge pattern).
import { describe, it, expect, vi } from 'vitest'
import { render, fireEvent } from '@testing-library/react'
import { CapacityPill } from './CapacityPill'
import { OverloadBanner } from './OverloadBanner'

const q = (c: HTMLElement, id: string) => c.querySelector(`[data-testid="${id}"]`)

describe('CapacityPill (render)', () => {
  it('null capacity → renders nothing (no pill, never "0/0")', () => {
    const { container } = render(<CapacityPill capacity={null} />)
    expect(q(container, 'capacity-pill')).toBeNull()
  })

  it('null estimatedMax → "N aktiv", not full', () => {
    const { container } = render(<CapacityPill capacity={{ current: 3, estimatedMax: null }} />)
    expect(q(container, 'capacity-pill')?.textContent).toBe('3 aktiv')
    expect(q(container, 'capacity-pill')?.classList.contains('full')).toBe(false)
    expect(q(container, 'capacity-pill.full')).toBeNull()
  })

  it('headroom → "N/M" neutral (no full marker, no full class)', () => {
    const { container } = render(<CapacityPill capacity={{ current: 2, estimatedMax: 6 }} />)
    expect(q(container, 'capacity-pill')?.textContent).toBe('2/6')
    expect(q(container, 'capacity-pill')?.classList.contains('full')).toBe(false)
    expect(q(container, 'capacity-pill')?.getAttribute('aria-label')).toContain('geschätzt')
  })

  it('full → WARN class + "voll" marker + a11y "voll"', () => {
    const { container } = render(<CapacityPill capacity={{ current: 6, estimatedMax: 6 }} />)
    expect(q(container, 'capacity-pill')?.classList.contains('full')).toBe(true)
    expect(q(container, 'capacity-pill.full')?.textContent).toBe('voll')
    expect(q(container, 'capacity-pill')?.getAttribute('aria-label')).toContain('voll')
  })
})

describe('OverloadBanner (render)', () => {
  it('is an assertive alert with the ▲ glyph, and Dismiss fires onDismiss', () => {
    const onDismiss = vi.fn()
    const { container } = render(<OverloadBanner onDismiss={onDismiss} />)
    const banner = q(container, 'overload-banner')
    expect(banner?.getAttribute('role')).toBe('alert')
    expect(banner?.textContent).toContain('▲')
    expect(banner?.textContent).toContain('abgelehnt')
    fireEvent.click(q(container, 'overload-banner.dismiss') as Element)
    expect(onDismiss).toHaveBeenCalledTimes(1)
  })
})
