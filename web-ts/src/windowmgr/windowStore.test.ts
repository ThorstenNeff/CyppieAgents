import { describe, it, expect, beforeEach } from 'vitest'
import { useWindowStore } from './windowStore'
import type { WindowState } from './windowState'

const win = (id: string, over: Partial<WindowState> = {}): WindowState => ({
  id,
  title: id,
  x: 0,
  y: 0,
  width: 400,
  height: 300,
  ...over,
})

const state = () => useWindowStore.getState()

describe('windowStore', () => {
  beforeEach(() => {
    useWindowStore.setState({ windows: [], windowOrder: [], host: { width: 1000, height: 800 }, contentIds: new Set() })
  })

  it('add appends; focus brings to front; content flag drives the resize floor', () => {
    state().add(win('a'), true) // content
    state().add(win('b'))
    expect(state().windows.map((w) => w.id)).toEqual(['a', 'b'])
    state().focus('a')
    expect(state().windows.map((w) => w.id)).toEqual(['b', 'a'])
  })

  it('moveBy applies the CYP-26 keep-in-viewport clamp', () => {
    state().add(win('a', { x: 0, y: 0, width: 400, height: 300 }))
    state().moveBy('a', -5000, -5000)
    const a = state().windows[0]
    expect(a.y).toBe(0) // title bar stays reachable
    expect(a.x).toBe(48 - 400) // 48px stays on screen
  })

  it('resizeBy honors the content floor and the host maximum', () => {
    state().add(win('a', { width: 320, height: 303 }), true) // content
    state().resizeBy('a', -10_000, -10_000)
    expect(state().windows[0]).toMatchObject({ width: 320, height: 303 }) // never below content floor
    state().resizeBy('a', 10_000, 10_000)
    expect(state().windows[0]).toMatchObject({ width: 1000, height: 800 }) // capped to host
  })

  it('setHost re-clamps oversized windows back into view', () => {
    state().add(win('a', { width: 900, height: 700 }))
    state().setHost(500, 400)
    expect(state().windows[0]).toMatchObject({ width: 500, height: 400 })
  })

  it('remove drops the window and its content flag', () => {
    state().add(win('a'), true)
    state().remove('a')
    expect(state().windows).toHaveLength(0)
    expect(state().contentIds.has('a')).toBe(false)
  })

  // CYP-664 (load-bearing): windowOrder is the STABLE registration order for the phone-pager pages. add/remove
  // maintain it; focus/move/resize/setHost must NEVER reorder it (proof the desktop z-order path is untouched and
  // the pager pages never jump on focus). Mutation = focus reorders windowOrder → this goes RED.
  it('windowOrder is stable registration order — maintained by add/remove, never reordered by focus/move/resize', () => {
    state().add(win('a'))
    state().add(win('b'))
    state().add(win('c'))
    expect(state().windowOrder).toEqual(['a', 'b', 'c'])

    state().focus('a') // z-order → [b, c, a], but the registration order must stay put
    expect(state().windows.map((w) => w.id)).toEqual(['b', 'c', 'a'])
    expect(state().windowOrder).toEqual(['a', 'b', 'c'])

    state().moveBy('b', 10, 10)
    state().resizeBy('c', 20, 20)
    state().setHost(900, 700)
    expect(state().windowOrder).toEqual(['a', 'b', 'c']) // untouched by geometry actions

    state().remove('b')
    expect(state().windowOrder).toEqual(['a', 'c']) // remove drops from the registration order too
    expect(state().windows.map((w) => w.id)).toEqual(['c', 'a'])

    state().add(win('a')) // idempotent re-add never double-registers
    expect(state().windowOrder).toEqual(['a', 'c'])
  })
})
