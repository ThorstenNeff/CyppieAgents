// @vitest-environment jsdom
// CYP-889 (NR-2) — the Canvas-position-preserving AC. Window geometry lives in the MODULE-LEVEL windowStore (Zustand),
// not in the canvas component, so switching to an agent destination (canvas unmounts) and back does NOT reset window
// positions — and the remounted canvas reads the LIVE store, not a stale snapshot. This is the guarantee the shared-VM
// (NR-3) builds on; here we pin the position half.
import { describe, it, expect, afterEach, beforeEach } from 'vitest'
import { useState } from 'react'
import { render, cleanup, fireEvent } from '@testing-library/react'
import { NavRailShell } from './NavRailShell'
import { navDestinations } from './navDestinations'
import { useWindowStore } from '../windowmgr/windowStore'
import type { Agent } from '../types/generated/contract'

const agent = (id: string, role: Agent['role']): Agent => ({ id, name: id, role, worktree: id })

beforeEach(() => {
  ;(window as unknown as { innerWidth: number }).innerWidth = 1024
  ;(window as unknown as { innerHeight: number }).innerHeight = 768
  // reset the shared module store + give it a generous host so positions are not clamped
  useWindowStore.setState({ windows: [], windowOrder: [], contentIds: new Set<string>() })
  useWindowStore.getState().setHost(2000, 2000)
})
afterEach(cleanup)

function CanvasProbe() {
  const win = useWindowStore((s) => s.windows.find((w) => w.id === 'agent:po'))
  return <div data-testid="win-pos">{win ? `${win.x},${win.y}` : 'none'}</div>
}

function Harness() {
  const [active, setActive] = useState('canvas')
  const dests = navDestinations([agent('po', 'PO')])
  return (
    <>
      <button data-testid="go-po" onClick={() => setActive('po')} />
      <button data-testid="go-canvas" onClick={() => setActive('canvas')} />
      <NavRailShell destinations={dests} activeId={active} onSelect={setActive}>
        {(a) => (a.id === 'canvas' ? <CanvasProbe /> : <div data-testid="agent-pane" />)}
      </NavRailShell>
    </>
  )
}

describe('CYP-889 — Canvas-position-preserving across destination switches', () => {
  it('★ switching Canvas→agent→Canvas preserves the window position and re-reads the LIVE store', () => {
    useWindowStore.getState().add({ id: 'agent:po', title: 'PO', x: 100, y: 200, width: 300, height: 200 })
    const { getByTestId } = render(<Harness />)
    expect(getByTestId('win-pos').textContent).toBe('100,200')

    // leave Canvas (it unmounts) …
    fireEvent.click(getByTestId('go-po'))
    expect(getByTestId('agent-pane')).toBeTruthy()
    // … the window moves while away (store is the source of truth, not the unmounted canvas) …
    useWindowStore.getState().moveBy('agent:po', 40, 60)
    // … return to Canvas: it re-reads the LIVE store — position preserved + updated, never reset to a default.
    fireEvent.click(getByTestId('go-canvas'))
    expect(getByTestId('win-pos').textContent).toBe('140,260')
  })
})
