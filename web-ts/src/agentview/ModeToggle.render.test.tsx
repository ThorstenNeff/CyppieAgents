// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, fireEvent, cleanup } from '@testing-library/react'
import { ModeToggle } from './ModeToggle'

afterEach(cleanup)

describe('ModeToggle (CYP-406)', () => {
  it('the checked segment follows the server state (INTERACTIVE→shell, MEDIATED→orchestration)', () => {
    const { getByTestId, rerender } = render(<ModeToggle state="INTERACTIVE" operator onRequestMode={() => {}} />)
    expect(getByTestId('mode-shell').getAttribute('aria-checked')).toBe('true')
    expect(getByTestId('mode-orchestration').getAttribute('aria-checked')).toBe('false')
    rerender(<ModeToggle state="MEDIATED" operator onRequestMode={() => {}} />)
    expect(getByTestId('mode-orchestration').getAttribute('aria-checked')).toBe('true')
  })

  it('HANDING_OVER is non-optimistic: still orchestration + aria-busy, shell not yet checked', () => {
    const { getByTestId } = render(<ModeToggle state="HANDING_OVER" operator onRequestMode={() => {}} />)
    expect(getByTestId('mode-orchestration').getAttribute('aria-checked')).toBe('true')
    expect(getByTestId('mode-shell').getAttribute('aria-checked')).toBe('false')
    expect(getByTestId('mode-toggle').getAttribute('aria-busy')).toBe('true')
  })

  it('operator click requests the mode; a non-operator is disabled, shows the note, and cannot drive it', () => {
    const onReq = vi.fn()
    const { getByTestId, queryByTestId, rerender } = render(<ModeToggle state="MEDIATED" operator onRequestMode={onReq} />)
    fireEvent.click(getByTestId('mode-shell'))
    expect(onReq).toHaveBeenCalledWith('shell')

    rerender(<ModeToggle state="MEDIATED" operator={false} onRequestMode={onReq} />)
    expect(getByTestId('mode-shell').getAttribute('aria-disabled')).toBe('true')
    expect(queryByTestId('mode-operator-only')).not.toBeNull()
    fireEvent.click(getByTestId('mode-shell'))
    expect(onReq).toHaveBeenCalledTimes(1) // the non-operator click was ignored
  })

  it('does not request while a hand-off is pending', () => {
    const onReq = vi.fn()
    const { getByTestId } = render(<ModeToggle state="HANDING_OVER" operator onRequestMode={onReq} />)
    fireEvent.click(getByTestId('mode-shell'))
    expect(onReq).not.toHaveBeenCalled()
  })
})
