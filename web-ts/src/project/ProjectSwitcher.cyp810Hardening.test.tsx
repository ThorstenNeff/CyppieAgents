// @vitest-environment jsdom
// CYP-810 SWITCHER (G-c2 + G-c3 + Dev5 select-G4) — ProjectSwitcher honesty:
//   G-c2  the <option> shows the project NAME, not the id — a `{p.name}→{p.id}` swap left the user seeing raw ids
//         while tests (which read only select.value/onSwitch(id) — both ids) stayed green.
//   G-c3  the select is DISABLED while a switch is pending (non-optimistic, documented) — no test drove a pending
//         cycle, so dropping `|| pending` (re-enabling mid-flight) survived.
//   G4    the control is a native, keyboard-accessible <select> with an aria-label (role-agnostic affordance).
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, cleanup, fireEvent } from '@testing-library/react'
import { ProjectSwitcher } from './ProjectSwitcher'
import type { ProjectsView } from '../types/generated/contract'

afterEach(cleanup)
// names DISTINCT from ids, so a name→id swap is observable (Alpha ≠ p1).
const view: ProjectsView = { activeProjectId: 'p1', projects: [{ id: 'p1', name: 'Alpha' }, { id: 'p2', name: 'Beta' }] }

describe('CYP-810 G-c2 — ProjectSwitcher options show the NAME, not the id', () => {
  it('★ each <option> renders the project name (mutation {p.name}→{p.id} shows raw ids → REDs)', () => {
    const { container } = render(<ProjectSwitcher projects={view} operator onSwitch={vi.fn().mockResolvedValue(undefined)} />)
    const opts = Array.from(container.querySelectorAll('option'))
    expect(opts.map((o) => o.textContent?.trim())).toEqual(['Alpha', 'Beta'])
    expect(opts.map((o) => o.getAttribute('value'))).toEqual(['p1', 'p2']) // value stays the id (the wire key)
  })
})

describe('CYP-810 G-c3 — the switch is non-optimistic: the select disables while a switch is pending', () => {
  const sel = (c: HTMLElement) => c.querySelector('[data-testid="project-switcher.select"]') as HTMLSelectElement

  it('★ operator + not pending → ENABLED; while a switch is in flight → DISABLED (drop `|| pending` REDs)', () => {
    let resolve: () => void = () => {}
    const onSwitch = vi.fn(() => new Promise<void>((r) => (resolve = r))) // never resolves until we say so
    const { container } = render(<ProjectSwitcher projects={view} operator onSwitch={onSwitch} />)
    expect(sel(container).disabled).toBe(false) // an operator's control is enabled up front
    fireEvent.change(sel(container), { target: { value: 'p2' } }) // start a heavyweight switch
    expect(sel(container).disabled).toBe(true) // pending → locked (non-optimistic; can't fire a second switch)
    resolve()
  })

  it('★ a non-operator can never flip the shared active project (disabled)', () => {
    const { container } = render(<ProjectSwitcher projects={view} operator={false} onSwitch={vi.fn()} />)
    expect(sel(container).disabled).toBe(true)
  })

  it('★ G4: the control is a native <select> with an aria-label (accessible, keyboard-native)', () => {
    const { container } = render(<ProjectSwitcher projects={view} operator onSwitch={vi.fn().mockResolvedValue(undefined)} />)
    const control = sel(container)
    expect(control.tagName).toBe('SELECT')
    expect(control.getAttribute('aria-label')?.trim()).toBeTruthy()
  })
})
