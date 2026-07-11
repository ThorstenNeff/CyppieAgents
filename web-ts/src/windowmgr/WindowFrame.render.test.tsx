// @vitest-environment jsdom
// CYP-402 (W4) — render + focus smoke test for the DOM window manager. Pointer-drag geometry is unit-tested in
// windowReducer/windowStore; this proves the components render at their state position and that pointerdown
// focuses (brings to front) via native DOM stacking.
import { describe, it, expect, beforeEach } from 'vitest'
import { render, fireEvent } from '@testing-library/react'
import { WindowHost } from './WindowHost'
import { WindowFrame } from './WindowFrame'
import { useWindowStore } from './windowStore'
import type { WindowState } from './windowState'

const win = (id: string, over: Partial<WindowState> = {}): WindowState => ({ id, title: id, x: 10, y: 20, width: 400, height: 300, ...over })

describe('WindowHost + WindowFrame', () => {
  beforeEach(() => {
    useWindowStore.setState({ windows: [], host: { width: 1000, height: 800 }, contentIds: new Set() })
  })

  it('renders each window by z-order at its state position', () => {
    useWindowStore.getState().add(win('a', { x: 10, y: 20 }))
    useWindowStore.getState().add(win('b'))
    const { container } = render(<WindowHost>{(w) => <WindowFrame window={w}>body</WindowFrame>}</WindowHost>)

    const frames = container.querySelectorAll('section.window')
    expect(frames).toHaveLength(2)
    const a = container.querySelector('[data-window-id="a"]') as HTMLElement
    expect(a.style.transform).toBe('translate(10px, 20px)')
    expect(a.style.width).toBe('400px')
  })

  it('pointerdown on a background window brings it to front (focus)', () => {
    useWindowStore.getState().add(win('a'))
    useWindowStore.getState().add(win('b'))
    expect(useWindowStore.getState().windows.map((w) => w.id)).toEqual(['a', 'b'])

    const { container } = render(<WindowHost>{(w) => <WindowFrame window={w}>body</WindowFrame>}</WindowHost>)
    fireEvent.pointerDown(container.querySelector('[data-window-id="a"]')!)
    expect(useWindowStore.getState().windows.map((w) => w.id)).toEqual(['b', 'a'])
  })
})
