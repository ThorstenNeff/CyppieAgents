// @vitest-environment jsdom
// CYP-645 — render teeth for the composer-history stepper. Container-scoped (no auto-cleanup in this repo).
import { describe, it, expect, vi } from 'vitest'
import { render, fireEvent } from '@testing-library/react'
import { ComposerHistoryStepper } from './ComposerHistoryStepper'
import { MAX_HISTORY_SIZE } from './inputHistory'

const q = (c: HTMLElement, id: string) => c.querySelector(`[data-testid="${id}"]`)

describe('ComposerHistoryStepper (render)', () => {
  it('shows N and emits size±1 on the buttons', () => {
    const onChange = vi.fn()
    const { container } = render(<ComposerHistoryStepper size={20} onChange={onChange} />)
    expect(q(container, 'composer-history-stepper.value')?.textContent).toBe('20')
    fireEvent.click(q(container, 'composer-history-stepper.inc') as Element)
    expect(onChange).toHaveBeenCalledWith(21)
    fireEvent.click(q(container, 'composer-history-stepper.dec') as Element)
    expect(onChange).toHaveBeenCalledWith(19)
  })

  it('at 0: dec disabled (never emits < 0), 0 shown as an honest "aus" value', () => {
    const onChange = vi.fn()
    const { container } = render(<ComposerHistoryStepper size={0} onChange={onChange} />)
    expect((q(container, 'composer-history-stepper.dec') as HTMLButtonElement).disabled).toBe(true)
    expect((q(container, 'composer-history-stepper.inc') as HTMLButtonElement).disabled).toBe(false)
    expect(q(container, 'composer-history-stepper.value')?.textContent).toContain('aus')
  })

  it('at MAX: inc disabled (never emits > MAX)', () => {
    const { container } = render(<ComposerHistoryStepper size={MAX_HISTORY_SIZE} onChange={() => {}} />)
    expect((q(container, 'composer-history-stepper.inc') as HTMLButtonElement).disabled).toBe(true)
    expect((q(container, 'composer-history-stepper.dec') as HTMLButtonElement).disabled).toBe(false)
  })

  it('the group carries an a11y name with the current depth', () => {
    const { container } = render(<ComposerHistoryStepper size={7} onChange={() => {}} />)
    expect(q(container, 'composer-history-stepper')?.getAttribute('aria-label')).toContain('7')
  })
})
