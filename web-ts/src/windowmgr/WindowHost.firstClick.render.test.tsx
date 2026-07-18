// @vitest-environment jsdom
// CYP-692 — the first click on an UNFOCUSED window was swallowed (reported against the ACL matrix; the root cause
// is the window manager, so every unfocused window was affected).
//
// Mechanism: `windows` is the z-order list and `bringToFront` splices the focused window to its END. WindowHost
// used to map that list with `key={win.id}`, so focusing MOVED the window's DOM node. A `click` only fires when
// pointerdown and pointerup land on the same element — the pointerdown that focused the window moved the node out
// from under its own pointerup, so no click was ever produced. The second click worked because bringToFront
// early-returns once the window is already last.
//
// WHY THESE TEETH TEST THE MECHANISM AND NOT THE CLICK: jsdom does not implement the browser's click-target
// algorithm — it fires whatever click event you dispatch, regardless of DOM moves. That is the SAME blind spot
// that let the original bug ship green (and why Tester2 could only reproduce it with a real pointer sequence:
// programmatic `.click()` bypasses pointerdown/up entirely). So a "click still fires" assertion here would be
// vacuous — it would pass against the BROKEN code too. Instead these pin the causal step: focus must not move the
// node. That is mutation-RED against the old implementation, which is what a regression tooth has to be.
// The end-to-end click behaviour is Tester2's real-pointer harness, not this file.
import { describe, it, expect, beforeEach } from 'vitest'
import { render, act, cleanup } from '@testing-library/react'
import { WindowHost } from './WindowHost'
import { useWindowStore } from './windowStore'
import type { WindowState } from './windowState'

const win = (id: string): WindowState => ({ id, title: id.toUpperCase(), x: 0, y: 0, width: 400, height: 300 })

// slot order in DOM sequence, identified by the child's marker (the slot div itself carries no id attribute)
const slotIds = (container: HTMLElement): string[] =>
  Array.from(container.querySelectorAll('.window-slot')).map((el) => el.querySelector('[data-win]')?.getAttribute('data-win') ?? '')

const slotOf = (container: HTMLElement, id: string): HTMLElement =>
  container.querySelector(`[data-win="${id}"]`)!.closest('.window-slot') as HTMLElement

const zIndexOf = (container: HTMLElement, id: string): number => Number(slotOf(container, id).style.zIndex)

const renderHost = () =>
  render(
    <WindowHost>
      {(w) => <div data-win={w.id}>{w.title}</div>}
    </WindowHost>,
  )

beforeEach(() => {
  cleanup()
  useWindowStore.setState({
    windows: [win('a'), win('b'), win('c')],
    windowOrder: ['a', 'b', 'c'],
    host: { width: 1000, height: 800 },
    contentIds: new Set(),
  })
})

describe('CYP-692 — focus must not move a window in the DOM', () => {
  it('★ focusing a background window leaves the DOM order UNCHANGED (the swallowed-click cause)', () => {
    const { container } = renderHost()
    const before = slotIds(container)
    expect(before).toEqual(['a', 'b', 'c'])
    act(() => useWindowStore.getState().focus('a')) // 'a' is bottom-most → the reorder case that broke the click
    expect(slotIds(container)).toEqual(before) // no node moved → pointerdown/pointerup stay on the same element
  })

  // NOTE — documentation, not a discriminating tooth: measured green against the BROKEN implementation too,
  // because React reorders a keyed child by MOVING the node rather than remounting it. Node identity was never
  // the problem; its POSITION was. Kept because "the window is not remounted on focus" is a property worth
  // stating (a remount would also lose in-progress input), but the ★ test above is the one that catches CYP-692.
  it('the SAME DOM node survives the focus (identity preserved, not re-created)', () => {
    const { container } = renderHost()
    const node = slotOf(container, 'a')
    act(() => useWindowStore.getState().focus('a'))
    expect(slotOf(container, 'a')).toBe(node) // same object — never remounted
  })

  it('stacking still follows focus — via zIndex, which is what actually paints the order', () => {
    const { container } = renderHost()
    expect(zIndexOf(container, 'c')).toBeGreaterThan(zIndexOf(container, 'a')) // 'c' starts on top
    act(() => useWindowStore.getState().focus('a'))
    expect(zIndexOf(container, 'a')).toBeGreaterThan(zIndexOf(container, 'b'))
    expect(zIndexOf(container, 'a')).toBeGreaterThan(zIndexOf(container, 'c')) // focused window is now top-most
  })

  it('every window renders even if windowOrder desyncs — a missing entry must never un-render a window', () => {
    useWindowStore.setState({ windows: [win('a'), win('b')], windowOrder: ['a'] }) // 'b' missing from the order
    const { container } = renderHost()
    expect(slotIds(container).sort()).toEqual(['a', 'b'])
  })
})
