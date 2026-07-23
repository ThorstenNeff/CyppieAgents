// @vitest-environment jsdom
// CYP-810 #4 (Dev5 render-lens) — EventRow selectable affordance is ROLE-AGNOSTIC and KEYBOARD-operable. A selectable
// row is a `<li role="button" tabIndex=0 onClick onKeyDown(Enter/Space)>` — NOT a `<button>`. onSelect-via-click is
// exercised elsewhere, but the ROLE + tabIndex + KEYBOARD activation were unpinned → dropping onKeyDown (mouse-only,
// keyboard-inaccessible) or the role/tabIndex would survive. This pins the accessible-control contract.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, cleanup, fireEvent } from '@testing-library/react'
import { EventRow } from './EventRow'
import type { EventSurrogate } from '../types/generated/contract'

afterEach(cleanup)
const ev: EventSurrogate = { id: 'e1', seq: 1, ts: 1, agentId: 'backend', projectId: 'p', type: 'agent.activity', severity: 'info' }

describe('CYP-810 #4 — EventRow selectable row is a keyboard-operable role=button (not just a mouse onClick)', () => {
  const row = (onSelect?: () => void) =>
    render(<EventRow event={ev} testid="event.row.e1" onSelect={onSelect} />).getByTestId('event.row.e1')

  it('★ a selectable row exposes role="button" + tabIndex=0 (accessible control, not an inert <li>)', () => {
    const r = row(vi.fn())
    expect(r.getAttribute('role')).toBe('button')
    expect(r.getAttribute('tabindex')).toBe('0')
  })

  it('★ Enter and Space activate onSelect (drop onKeyDown → keyboard-inaccessible → REDs)', () => {
    const onSelect = vi.fn()
    const r = row(onSelect)
    fireEvent.keyDown(r, { key: 'Enter' })
    fireEvent.keyDown(r, { key: ' ' })
    expect(onSelect).toHaveBeenCalledTimes(2)
  })

  it('click also activates onSelect (mouse path, for completeness)', () => {
    const onSelect = vi.fn()
    fireEvent.click(row(onSelect))
    expect(onSelect).toHaveBeenCalledTimes(1)
  })

  it('★ a NON-selectable row (no onSelect) is inert — no role=button, no tabIndex (non-vacuity of the above)', () => {
    const r = row(undefined)
    expect(r.getAttribute('role')).toBeNull()
    expect(r.getAttribute('tabindex')).toBeNull()
  })
})
