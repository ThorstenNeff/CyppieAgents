// @vitest-environment jsdom
// CYP-651 — render teeth for the workspace-bar project switcher.
import { describe, it, expect, vi } from 'vitest'
import { render, fireEvent } from '@testing-library/react'
import { ProjectSwitcher } from './ProjectSwitcher'
import type { ProjectsView } from '../types/generated/contract'

const q = (c: HTMLElement, id: string) => c.querySelector(`[data-testid="${id}"]`)
const view: ProjectsView = { activeProjectId: 'alpha', projects: [{ id: 'alpha', name: 'Alpha' }, { id: 'beta', name: 'Beta' }] }

describe('ProjectSwitcher (render)', () => {
  it('reflects the active project (non-optimistic value) and switches on change', () => {
    const onSwitch = vi.fn().mockResolvedValue(undefined)
    const { container } = render(<ProjectSwitcher projects={view} operator onSwitch={onSwitch} />)
    const sel = q(container, 'project-switcher.select') as HTMLSelectElement
    expect(sel.value).toBe('alpha')
    fireEvent.change(sel, { target: { value: 'beta' } })
    expect(onSwitch).toHaveBeenCalledWith('beta')
  })

  it('a switch-to-active change is a no-op (no heavyweight POST)', () => {
    const onSwitch = vi.fn().mockResolvedValue(undefined)
    const { container } = render(<ProjectSwitcher projects={view} operator onSwitch={onSwitch} />)
    fireEvent.change(q(container, 'project-switcher.select') as Element, { target: { value: 'alpha' } })
    expect(onSwitch).not.toHaveBeenCalled()
  })

  it('non-operator → the select is disabled (only an operator flips the shared active project)', () => {
    const { container } = render(<ProjectSwitcher projects={view} operator={false} onSwitch={() => Promise.resolve()} />)
    expect((q(container, 'project-switcher.select') as HTMLSelectElement).disabled).toBe(true)
  })

  it('fewer than 2 projects (or null) → no switcher rendered', () => {
    const { container: a } = render(<ProjectSwitcher projects={{ activeProjectId: 'solo', projects: [{ id: 'solo', name: 'Solo' }] }} operator onSwitch={() => Promise.resolve()} />)
    expect(q(a, 'project-switcher')).toBeNull()
    const { container: b } = render(<ProjectSwitcher projects={null} operator onSwitch={() => Promise.resolve()} />)
    expect(q(b, 'project-switcher')).toBeNull()
  })
})
