// @vitest-environment jsdom
// CYP-888 (NR-1) — the shell wiring tooth: the responsive gate must control whether the rail mounts, and below the gate
// the children must render UNCHANGED (no wrapper). jsdom computes no layout, so this pins the STRUCTURE (presence/
// absence of the rail + content-pane and that children survive), not the visual — the CSS layout is guarded separately
// in navRailCss.honesty.test.ts. The gate arithmetic itself is in navRailGate.test.ts.
import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup, act } from '@testing-library/react'
import { NavRailShell } from './NavRailShell'

const DEFAULT_W = 1024
const DEFAULT_H = 768

function setViewport(width: number, height: number): void {
  ;(window as unknown as { innerWidth: number }).innerWidth = width
  ;(window as unknown as { innerHeight: number }).innerHeight = height
}

afterEach(() => {
  cleanup()
  setViewport(DEFAULT_W, DEFAULT_H) // restore jsdom default so other suites are unaffected
})

const child = <div data-testid="canvas-child">canvas</div>

describe('CYP-888 — NavRailShell responsive gating', () => {
  it('★ landscape + short-edge ≥600dp → rail + content-pane mount, children inside the pane', () => {
    setViewport(1024, 768)
    const { getByTestId } = render(<NavRailShell>{child}</NavRailShell>)
    expect(getByTestId('nav-rail-layout')).toBeTruthy()
    expect(getByTestId('nav-rail')).toBeTruthy()
    // children live INSIDE the content pane (not displaced by the rail mount)
    expect(getByTestId('nav-content-pane').contains(getByTestId('canvas-child'))).toBe(true)
  })

  it('★ portrait → NO rail, children render UNCHANGED (no wrapper)', () => {
    // MUT: make NavRailShell ignore the gate (always render the rail) → nav-rail appears in portrait → this reds.
    setViewport(768, 1024)
    const { queryByTestId, getByTestId } = render(<NavRailShell>{child}</NavRailShell>)
    expect(queryByTestId('nav-rail')).toBeNull()
    expect(queryByTestId('nav-rail-layout')).toBeNull()
    expect(queryByTestId('nav-content-pane')).toBeNull()
    expect(getByTestId('canvas-child')).toBeTruthy() // canvas unchanged
  })

  it('★ landscape phone (short-edge <600dp) → NO rail', () => {
    setViewport(900, 500)
    const { queryByTestId, getByTestId } = render(<NavRailShell>{child}</NavRailShell>)
    expect(queryByTestId('nav-rail')).toBeNull()
    expect(getByTestId('canvas-child')).toBeTruthy()
  })

  it('★ live flip: a resize from portrait to landscape≥600 mounts the rail (hook wiring)', () => {
    // MUT: drop the resize listener in useNavRail → the rail never appears after the resize → this reds.
    setViewport(768, 1024)
    const { queryByTestId } = render(<NavRailShell>{child}</NavRailShell>)
    expect(queryByTestId('nav-rail')).toBeNull()
    act(() => {
      setViewport(1200, 800)
      window.dispatchEvent(new Event('resize'))
    })
    expect(queryByTestId('nav-rail')).toBeTruthy()
  })
})
