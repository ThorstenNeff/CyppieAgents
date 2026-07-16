// @vitest-environment jsdom
// CYP-649 — render teeth for the compact panel + numeric editor. Container-scoped (no auto-cleanup in this repo).
import { describe, it, expect, vi } from 'vitest'
import { render, fireEvent } from '@testing-library/react'
import { CompactPanel } from './CompactPanel'
import { NumericEditor } from './NumericEditor'
import { COMPACT_BOUNDS, formatCompactTokens } from './compactModel'
import type { CompactStatus } from '../types/generated/contract'

const q = (c: HTMLElement, id: string) => c.querySelector(`[data-testid="${id}"]`)
const status = (o: Partial<CompactStatus> = {}): CompactStatus => ({ allowed: true, thresholdTokens: 500_000, armed: false, running: false, staggerMs: 120_000, roundGapMs: 120_000, roundWindowMs: 600_000, ...o })

describe('CompactPanel (render)', () => {
  it('operator + resolved status → allow checkbox, editors, facts', () => {
    const { container } = render(<CompactPanel status={status()} operator onSetConfig={() => Promise.resolve()} />)
    expect(q(container, 'compact-panel.allow')).not.toBeNull()
    expect(q(container, 'compact-threshold')).not.toBeNull()
    expect(q(container, 'compact-stagger')).not.toBeNull()
    expect(q(container, 'compact-roundgap')).not.toBeNull()
    expect(q(container, 'compact-roundwindow')).not.toBeNull()
    expect(q(container, 'compact-panel.status')?.textContent).toBe('bereit') // allowed + not running → idle
    expect(q(container, 'compact-panel.gate')).toBeNull() // operator sees no member gate hint
  })

  it('member → read-only chip + gate hint, NO editors, NO checkbox', () => {
    const { container } = render(<CompactPanel status={status({ allowed: false })} operator={false} onSetConfig={() => Promise.resolve()} />)
    expect(q(container, 'compact-panel.allow')).toBeNull()
    expect(q(container, 'compact-panel.allow-chip')?.textContent).toContain('nein')
    expect(q(container, 'compact-panel.gate')).not.toBeNull()
    expect(q(container, 'compact-threshold')).toBeNull()
  })

  it('unknown status (null) → facts + editors ABSENT (fail-closed), hints still shown', () => {
    // RED if a null status ever renders defaulted facts/editors — unknown must be honest absence.
    const { container } = render(<CompactPanel status={null} operator onSetConfig={() => Promise.resolve()} />)
    expect(q(container, 'compact-panel.facts')).toBeNull()
    expect(q(container, 'compact-threshold')).toBeNull()
    expect(q(container, 'compact-panel.info')).not.toBeNull()
    // allow checkbox is present (fail-closed unchecked) so an operator can enable from unknown
    expect((q(container, 'compact-panel.allow') as HTMLInputElement).checked).toBe(false)
  })

  it('toggling allow posts {allowed} (non-optimistic — checked follows the status prop, not the click)', () => {
    const onSetConfig = vi.fn().mockResolvedValue(undefined)
    const { container } = render(<CompactPanel status={status({ allowed: false })} operator onSetConfig={onSetConfig} />)
    const box = q(container, 'compact-panel.allow') as HTMLInputElement
    expect(box.checked).toBe(false)
    fireEvent.click(box)
    expect(onSetConfig).toHaveBeenCalledWith({ allowed: true })
  })

  it('running status shows the running label (not idle/off)', () => {
    const { container } = render(<CompactPanel status={status({ allowed: true, running: true })} operator onSetConfig={() => Promise.resolve()} />)
    expect(q(container, 'compact-panel.status')?.textContent).toContain('läuft')
  })
})

describe('NumericEditor (render)', () => {
  it('valid change → Set enabled + preview; out-of-range → error + Set disabled', () => {
    const onSet = vi.fn()
    const { container } = render(
      <NumericEditor label="Schwellwert" current={500_000} bounds={COMPACT_BOUNDS.thresholdTokens} preview={formatCompactTokens} editable onSet={onSet} testId="ed" />,
    )
    const input = q(container, 'ed.input') as HTMLInputElement
    fireEvent.change(input, { target: { value: '750000' } })
    expect(q(container, 'ed.preview')?.textContent).toContain('750K')
    expect((q(container, 'ed.set') as HTMLButtonElement).disabled).toBe(false)
    fireEvent.click(q(container, 'ed.set') as Element)
    expect(onSet).toHaveBeenCalledWith(750_000)

    fireEvent.change(input, { target: { value: '0' } }) // digit-filtered '0' → below min 1
    expect(q(container, 'ed.error')).not.toBeNull()
    expect((q(container, 'ed.set') as HTMLButtonElement).disabled).toBe(true)
  })

  it('unchanged value → Set disabled (no-op)', () => {
    const { container } = render(
      <NumericEditor label="x" current={500_000} bounds={COMPACT_BOUNDS.thresholdTokens} preview={formatCompactTokens} editable onSet={() => {}} testId="ed" />,
    )
    expect((q(container, 'ed.set') as HTMLButtonElement).disabled).toBe(true) // draft == current
  })

  it('not editable → input + Set disabled', () => {
    const { container } = render(
      <NumericEditor label="x" current={500_000} bounds={COMPACT_BOUNDS.thresholdTokens} preview={formatCompactTokens} editable={false} onSet={() => {}} testId="ed" />,
    )
    expect((q(container, 'ed.input') as HTMLInputElement).disabled).toBe(true)
    expect((q(container, 'ed.set') as HTMLButtonElement).disabled).toBe(true)
  })
})
