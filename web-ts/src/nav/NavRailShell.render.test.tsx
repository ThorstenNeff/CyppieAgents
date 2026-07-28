// @vitest-environment jsdom
// CYP-888 (NR-1) — the responsive-gate wiring tooth: the gate controls whether the rail mounts, and below the gate the
// pane renders the CANVAS destination UNCHANGED (no rail wrapper). jsdom computes no layout, so this pins STRUCTURE
// (rail present/absent + canvas survives), not the visual (guarded in navRailCss.honesty.test.ts). The gate arithmetic
// is in navRailGate.test.ts; the destination items/behaviour are in NavRailShell.nav.render.test.tsx.
import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup, act } from '@testing-library/react'
import { NavRailShell } from './NavRailShell'
import type { NavDestination } from './navDestinations'

const DEFAULT_W = 1024
const DEFAULT_H = 768

function setViewport(width: number, height: number): void {
  ;(window as unknown as { innerWidth: number }).innerWidth = width
  ;(window as unknown as { innerHeight: number }).innerHeight = height
}

afterEach(() => {
  cleanup()
  setViewport(DEFAULT_W, DEFAULT_H)
})

const dests: NavDestination[] = [{ id: 'canvas', kind: 'canvas', label: 'Canvas' }]
const shell = () => (
  <NavRailShell destinations={dests} activeId="canvas" onSelect={() => {}}>
    {(active) => <div data-testid="canvas-child">{active.id}</div>}
  </NavRailShell>
)

describe('CYP-888 — NavRailShell responsive gating', () => {
  it('★ landscape + short-edge ≥600dp → rail + content-pane mount, children inside the pane', () => {
    setViewport(1024, 768)
    const { getByTestId } = render(shell())
    expect(getByTestId('nav-rail-layout')).toBeTruthy()
    expect(getByTestId('nav-rail')).toBeTruthy()
    expect(getByTestId('nav-content-pane').contains(getByTestId('canvas-child'))).toBe(true)
  })

  it('★ portrait → NO rail, children render UNCHANGED (no wrapper)', () => {
    // MUT: make NavRailShell ignore the gate (always render the rail) → nav-rail appears in portrait → this reds.
    setViewport(768, 1024)
    const { queryByTestId, getByTestId } = render(shell())
    expect(queryByTestId('nav-rail')).toBeNull()
    expect(queryByTestId('nav-rail-layout')).toBeNull()
    expect(getByTestId('canvas-child')).toBeTruthy()
  })

  it('★ landscape phone (short-edge <600dp) → NO rail', () => {
    setViewport(900, 500)
    const { queryByTestId, getByTestId } = render(shell())
    expect(queryByTestId('nav-rail')).toBeNull()
    expect(getByTestId('canvas-child')).toBeTruthy()
  })

  it('★ unmeasured (0×0) → NO rail (never a layout jump before the real measure)', () => {
    setViewport(0, 0)
    const { queryByTestId, getByTestId } = render(shell())
    expect(queryByTestId('nav-rail')).toBeNull()
    expect(getByTestId('canvas-child')).toBeTruthy()
  })

  it('★ live flip: a resize from portrait to landscape≥600 mounts the rail (hook wiring)', () => {
    setViewport(768, 1024)
    const { queryByTestId } = render(shell())
    expect(queryByTestId('nav-rail')).toBeNull()
    act(() => {
      setViewport(1200, 800)
      window.dispatchEvent(new Event('resize'))
    })
    expect(queryByTestId('nav-rail')).toBeTruthy()
  })
})
