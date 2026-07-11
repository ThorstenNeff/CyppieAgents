// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, fireEvent, cleanup } from '@testing-library/react'
import { LifecycleHeader, type LifecycleHeaderProps } from './LifecycleHeader'

afterEach(cleanup)

const base = (over: Partial<LifecycleHeaderProps> = {}): LifecycleHeaderProps => ({
  agentId: 'backend',
  state: 'RUNNING',
  pending: undefined,
  operator: true,
  onStart: vi.fn(),
  onStop: vi.fn(),
  onRestart: vi.fn(),
  ...over,
})

describe('LifecycleHeader (CYP-431)', () => {
  it('shows the status label + a dot whose shape/role follow statusDotSpec', () => {
    const running = render(<LifecycleHeader {...base({ state: 'RUNNING' })} />)
    const dot = running.getByTestId('lifecycle.dot.backend')
    expect(dot.getAttribute('data-shape')).toBe('fill')
    expect(dot.getAttribute('data-role')).toBe('primary')
    expect(running.getByTestId('lifecycle.status.backend').textContent).toContain('Aktiv')
    cleanup()
    const unknown = render(<LifecycleHeader {...base({ state: 'UNKNOWN' })} />)
    const ring = unknown.getByTestId('lifecycle.dot.backend')
    expect(ring.getAttribute('data-shape')).toBe('ring') // CYP-396: UNKNOWN = ring
    expect(ring.getAttribute('data-role')).toBe('outline')
  })

  it('operator controls are enabled and fire; a pending request disables them + shows the transient label', () => {
    const onRestart = vi.fn()
    const { getByTestId } = render(<LifecycleHeader {...base({ onRestart })} />)
    fireEvent.click(getByTestId('lifecycle.restart.backend'))
    expect(onRestart).toHaveBeenCalledWith('backend')
    cleanup()
    // while pending: label is the transient, dot neutral, controls disabled (no stacking)
    const pending = render(<LifecycleHeader {...base({ pending: 'restart' })} />)
    expect(pending.getByTestId('lifecycle.status.backend').textContent).toContain('Neustart…')
    expect(pending.getByTestId('lifecycle.dot.backend').getAttribute('data-role')).toBe('neutral')
    expect((pending.getByTestId('lifecycle.restart.backend') as HTMLButtonElement).disabled).toBe(true)
  })

  it('a non-operator sees the controls PRESENT but DISABLED (no fake affordance) + the operator-only note', () => {
    const onStart = vi.fn()
    const { getByTestId } = render(<LifecycleHeader {...base({ operator: false, onStart })} />)
    const start = getByTestId('lifecycle.start.backend') as HTMLButtonElement
    expect(start).toBeTruthy() // present, not hidden
    expect(start.disabled).toBe(true)
    fireEvent.click(start)
    expect(onStart).not.toHaveBeenCalled()
    expect(getByTestId('lifecycle.operatorOnly.backend')).toBeTruthy()
  })

  it('CYP-369: all three controls are rendered and live in the non-shrinking controls cluster', () => {
    const { getByTestId } = render(<LifecycleHeader {...base()} />)
    const controls = getByTestId('lifecycle.controls.backend')
    expect(controls.className).toContain('lifecycle-controls') // flex-shrink:0 cluster (see index.css)
    for (const id of ['lifecycle.start.backend', 'lifecycle.stop.backend', 'lifecycle.restart.backend']) {
      expect(controls.contains(getByTestId(id))).toBe(true)
    }
  })
})
