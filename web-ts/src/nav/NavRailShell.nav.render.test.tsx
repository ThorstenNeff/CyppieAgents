// @vitest-environment jsdom
// CYP-889 (NR-2) — the nav behaviour teeth (rail ON, default landscape viewport): the rail renders one item per
// destination, exactly one is aria-current, selecting a non-active item fires onSelect while self-select is a no-op, the
// pane renders the active destination, a gone active id falls back to Canvas, the rail carries NO status (Nav≠Health),
// and the "Keine Worker" caption is a loaded-&-empty signal. Real navigation, not a tablist.
import { describe, it, expect, afterEach, vi } from 'vitest'
import { render, cleanup, fireEvent } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { NavRailShell } from './NavRailShell'
import { navDestinations } from './navDestinations'
import type { Agent } from '../types/generated/contract'

const agent = (id: string, role: Agent['role'], runState?: Agent['runState']): Agent => ({ id, name: id, role, worktree: id, runState })

function setLandscape(): void {
  ;(window as unknown as { innerWidth: number }).innerWidth = 1024
  ;(window as unknown as { innerHeight: number }).innerHeight = 768
}
setLandscape()
afterEach(cleanup)

const roster = [agent('po', 'PO'), agent('pl', 'PRODUCT_LEAD'), agent('w1', 'WORKER')]
const renderShell = (activeId: string, onSelect = vi.fn()) => {
  const dests = navDestinations(roster)
  const r = render(
    <NavRailShell destinations={dests} activeId={activeId} onSelect={onSelect}>
      {(active) => <div data-testid={`pane-${active.id}`}>{active.id}</div>}
    </NavRailShell>,
  )
  return { ...r, onSelect }
}

describe('CYP-889 — NavRailShell destinations + nav-state', () => {
  it('★ renders one item per destination (canvas, po, lead, worker, settings) with the parity testids', () => {
    const { getByTestId } = renderShell('canvas')
    expect(getByTestId('navRail.dest.canvas')).toBeTruthy()
    expect(getByTestId('navRail.dest.po.po')).toBeTruthy()
    expect(getByTestId('navRail.dest.lead.pl')).toBeTruthy()
    expect(getByTestId('navRail.dest.worker.w1')).toBeTruthy()
    expect(getByTestId('navRail.dest.settings')).toBeTruthy()
  })

  it('★ exactly ONE aria-current="page" — the active item', () => {
    // MUT: set aria-current on every item → more than one current → this reds.
    const { container, getByTestId } = renderShell('po')
    const current = container.querySelectorAll('[aria-current="page"]')
    expect(current.length).toBe(1)
    expect(getByTestId('navRail.dest.po.po').getAttribute('aria-current')).toBe('page')
    expect(getByTestId('navRail.dest.canvas').getAttribute('aria-current')).toBeNull()
  })

  it('★ selecting a NON-active destination fires onSelect; selecting the ACTIVE one is a no-op', () => {
    // MUT: drop the `if (!active)` guard → clicking the active item calls onSelect → the no-op assertion reds.
    const { getByTestId, onSelect } = renderShell('canvas')
    fireEvent.click(getByTestId('navRail.dest.worker.w1'))
    expect(onSelect).toHaveBeenCalledWith('w1')
    onSelect.mockClear()
    fireEvent.click(getByTestId('navRail.dest.canvas')) // canvas is active
    expect(onSelect).not.toHaveBeenCalled()
  })

  it('★ the pane renders the ACTIVE destination', () => {
    expect(renderShell('pl').getByTestId('pane-pl')).toBeTruthy()
    expect(renderShell('canvas').getByTestId('pane-canvas')).toBeTruthy()
  })

  it('★ a gone active id falls back to Canvas (no dead active target / pane)', () => {
    // MUT: remove the ?? canvas fallback in activeDestination → aria-current on nothing + pane-undefined → reds.
    const { getByTestId, container } = renderShell('ghost')
    expect(getByTestId('pane-canvas')).toBeTruthy()
    expect(getByTestId('navRail.dest.canvas').getAttribute('aria-current')).toBe('page')
    expect(container.querySelectorAll('[aria-current="page"]').length).toBe(1)
  })

  it('★ the rail carries NO status: an agent with runState=ERROR shows no status text on its item (Nav≠Health)', () => {
    const roster2 = [agent('po', 'PO', 'ERROR')]
    const { getByTestId } = render(
      <NavRailShell destinations={navDestinations(roster2)} activeId="canvas" onSelect={() => {}}>
        {(a) => <div data-testid={`pane-${a.id}`} />}
      </NavRailShell>,
    )
    const item = getByTestId('navRail.dest.po.po')
    expect(item.textContent).not.toMatch(/ERROR|RUNNING|STOPPED/)
  })

  it('★ runState is never READ in the rail source (Nav≠Health, structural guard)', () => {
    // MUT: bind runState to a rail item → this source-scan reds. Comments are stripped first — the point is that
    // runState is never read/rendered, not that the word is never mentioned (the code documents WHY it is excluded).
    const src = readFileSync(resolve(process.cwd(), 'src/nav/NavRailShell.tsx'), 'utf8')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\/\/.*$/gm, '')
    expect(src).not.toMatch(/runState/)
  })

  it('★ "Keine Worker" caption is a loaded-&-empty signal: shown with agents-but-no-workers, absent otherwise', () => {
    const withPoNoWorkers = navDestinations([agent('po', 'PO')])
    const { getByTestId, queryByTestId, rerender } = render(
      <NavRailShell destinations={withPoNoWorkers} activeId="canvas" onSelect={() => {}}>
        {(a) => <div data-testid={`pane-${a.id}`} />}
      </NavRailShell>,
    )
    expect(getByTestId('navRail.workersEmpty')).toBeTruthy()
    // with a worker present → no caption
    rerender(
      <NavRailShell destinations={navDestinations([agent('po', 'PO'), agent('w1', 'WORKER')])} activeId="canvas" onSelect={() => {}}>
        {(a) => <div data-testid={`pane-${a.id}`} />}
      </NavRailShell>,
    )
    expect(queryByTestId('navRail.workersEmpty')).toBeNull()
    // empty roster (nothing loaded) → NO confident-empty caption
    rerender(
      <NavRailShell destinations={navDestinations([])} activeId="canvas" onSelect={() => {}}>
        {(a) => <div data-testid={`pane-${a.id}`} />}
      </NavRailShell>,
    )
    expect(queryByTestId('navRail.workersEmpty')).toBeNull()
  })

  it('★ CYP-891 roster LOAD-ERROR → a retry affordance, NOT a confident-empty caption (error ≠ empty)', () => {
    // MUT: drop the error branch (show workersEmpty regardless) → rosterError absent + workersEmpty shown on error → reds.
    const onRetryRoster = vi.fn()
    const { getByTestId, queryByTestId } = render(
      <NavRailShell destinations={navDestinations([agent('po', 'PO')])} activeId="canvas" onSelect={() => {}} rosterLoadError onRetryRoster={onRetryRoster}>
        {(a) => <div data-testid={`pane-${a.id}`} />}
      </NavRailShell>,
    )
    expect(getByTestId('navRail.rosterError')).toBeTruthy()
    expect(queryByTestId('navRail.workersEmpty')).toBeNull() // a load error is NOT a loaded-&-empty roster
    fireEvent.click(getByTestId('navRail.rosterError.retry'))
    expect(onRetryRoster).toHaveBeenCalled()
    // the FIXED destinations still render (nav chrome must not fail on roster data)
    expect(getByTestId('navRail.dest.canvas')).toBeTruthy()
    expect(getByTestId('navRail.dest.settings')).toBeTruthy()
  })

  it('★ real navigation, NOT a tablist (no role=tab/tablist — avoids false tabpanel ARIA)', () => {
    const { container } = renderShell('canvas')
    expect(container.querySelector('[role="tab"]')).toBeNull()
    expect(container.querySelector('[role="tablist"]')).toBeNull()
  })
})
