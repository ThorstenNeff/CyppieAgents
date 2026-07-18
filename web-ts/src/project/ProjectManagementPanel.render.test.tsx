// @vitest-environment jsdom
// CYP-651 — render teeth for the project management panel, centred on the DESTRUCTIVE-DELETE guardrails: delete
// disabled for the active/last project (inline reason), and the hard delete armed ONLY by a matching name-echo.
// Container-scoped (no auto-cleanup in this repo).
import { describe, it, expect, vi } from 'vitest'
import { render, fireEvent } from '@testing-library/react'
import { ProjectManagementPanel } from './ProjectManagementPanel'
import type { ProjectsView } from '../types/generated/contract'

const q = (c: HTMLElement, id: string) => c.querySelector(`[data-testid="${id}"]`)
const view: ProjectsView = {
  activeProjectId: 'alpha',
  projects: [
    { id: 'alpha', name: 'Alpha' },
    { id: 'beta', name: 'Beta' },
  ],
}
const noop = () => Promise.resolve()
const panel = (over: Partial<React.ComponentProps<typeof ProjectManagementPanel>> = {}) => (
  <ProjectManagementPanel projects={view} operator onCreate={noop} onSwitch={noop} onRename={noop} onDelete={noop} {...over} />
)

describe('ProjectManagementPanel (render)', () => {
  it('member → gate hint, no list', () => {
    const { container } = render(panel({ operator: false }))
    expect(q(container, 'project-mgmt.gate')).not.toBeNull()
    expect(q(container, 'project-mgmt.list')).toBeNull()
  })

  it('active project: switch disabled + delete disabled with inline reason; non-active: delete enabled', () => {
    const { container } = render(panel())
    expect((q(container, 'project-mgmt.switch.alpha') as HTMLButtonElement).disabled).toBe(true) // active → no switch
    expect((q(container, 'project-mgmt.delete.alpha') as HTMLButtonElement).disabled).toBe(true) // active → no delete
    expect(q(container, 'project-mgmt.blocked.alpha')?.textContent).toContain('erst wechseln')
    expect((q(container, 'project-mgmt.delete.beta') as HTMLButtonElement).disabled).toBe(false) // non-active → deletable
  })

  it('a lone project → delete disabled with the last-project reason', () => {
    const lone: ProjectsView = { activeProjectId: 'solo', projects: [{ id: 'solo', name: 'Solo' }] }
    const { container } = render(panel({ projects: lone }))
    expect((q(container, 'project-mgmt.delete.solo') as HTMLButtonElement).disabled).toBe(true)
    expect(q(container, 'project-mgmt.blocked.solo')?.textContent).toContain('letzte')
  })

  it('the hard delete fires ONLY when the name-echo matches exactly', () => {
    const onDelete = vi.fn().mockResolvedValue(undefined)
    const { container } = render(panel({ onDelete }))
    fireEvent.click(q(container, 'project-mgmt.delete.beta') as Element) // open the delete form
    const confirm = q(container, 'project-mgmt.delete-confirm') as HTMLButtonElement
    expect(confirm.disabled).toBe(true) // no echo yet
    fireEvent.change(q(container, 'project-mgmt.delete-echo') as Element, { target: { value: 'Bet' } })
    expect(confirm.disabled).toBe(true) // partial → still disabled
    fireEvent.change(q(container, 'project-mgmt.delete-echo') as Element, { target: { value: 'Beta' } })
    expect(confirm.disabled).toBe(false) // exact match → armed
    fireEvent.click(confirm)
    expect(onDelete).toHaveBeenCalledWith('beta', false) // default: worktrees kept
  })

  it('switch calls onSwitch (non-optimistic — the view flip comes from the resolved 200)', () => {
    const onSwitch = vi.fn().mockResolvedValue(undefined)
    const { container } = render(panel({ onSwitch }))
    fireEvent.click(q(container, 'project-mgmt.switch.beta') as Element)
    expect(onSwitch).toHaveBeenCalledWith('beta')
  })
})

describe('ProjectManagementPanel — CYP-679 honest load-error + retry', () => {
  it('failed load (null + loadError) → error+retry, NOT the perpetual "loading…" placeholder; retry fires', () => {
    const onRetryLoad = vi.fn()
    const { container } = render(panel({ projects: null, loadError: true, onRetryLoad }))
    expect(q(container, 'project-mgmt.loadError')).not.toBeNull()
    expect(q(container, 'project-mgmt.loading')).toBeNull() // mutation: render loading instead of error → RED
    fireEvent.click(q(container, 'project-mgmt.loadError.retry') as HTMLElement)
    expect(onRetryLoad).toHaveBeenCalledTimes(1)
  })

  it('loading (null, no error) → the loading placeholder, not the error (non-vacuum contrast)', () => {
    const { container } = render(panel({ projects: null, loadError: false }))
    expect(q(container, 'project-mgmt.loading')).not.toBeNull()
    expect(q(container, 'project-mgmt.loadError')).toBeNull()
  })

  it('a member never sees the load-error — the gate hint wins even on failure', () => {
    const { container } = render(panel({ projects: null, loadError: true, operator: false }))
    expect(q(container, 'project-mgmt.gate')).not.toBeNull()
    expect(q(container, 'project-mgmt.loadError')).toBeNull()
  })
})
