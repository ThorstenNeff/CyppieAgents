import { describe, it, expect } from 'vitest'
import {
  invariantMinWidth,
  invariantMinHeight,
  bringToFront,
  moveBy,
  resizeBy,
  clampToBounds,
  clampSizeToBounds,
} from './windowReducer'
import type { WindowState } from './windowState'

const win = (id: string, over: Partial<WindowState> = {}): WindowState => ({
  id,
  title: id,
  x: 0,
  y: 0,
  width: 400,
  height: 400,
  ...over,
})

describe('WindowReducer — floors by window class', () => {
  it('content windows keep the larger width/height floor; others the plain floor', () => {
    expect(invariantMinWidth(true)).toBe(320)
    expect(invariantMinWidth(false)).toBe(160)
    expect(invariantMinHeight(true)).toBe(303)
    expect(invariantMinHeight(false)).toBe(120)
  })
})

describe('WindowReducer — focus / z-order (list order)', () => {
  it('bringToFront moves the window to the end (top-most); no-op if already front or absent', () => {
    const ws = [win('a'), win('b'), win('c')]
    expect(bringToFront(ws, 'a').map((w) => w.id)).toEqual(['b', 'c', 'a'])
    expect(bringToFront(ws, 'c').map((w) => w.id)).toEqual(['a', 'b', 'c']) // already front
    expect(bringToFront(ws, 'z').map((w) => w.id)).toEqual(['a', 'b', 'c']) // absent
  })
})

describe('WindowReducer — move / resize', () => {
  it('moveBy translates only the target window', () => {
    const ws = [win('a', { x: 10, y: 20 }), win('b', { x: 0, y: 0 })]
    const moved = moveBy(ws, 'a', 5, -7)
    expect(moved[0]).toMatchObject({ x: 15, y: 13 })
    expect(moved[1]).toMatchObject({ x: 0, y: 0 })
  })

  it('resizeBy clamps to the content floor — one drag cannot undo the width guarantee (CYP-373)', () => {
    const ws = [win('a', { width: 320, height: 303 })]
    const shrunk = resizeBy(ws, 'a', -10_000, -10_000, {
      minWidth: invariantMinWidth(true),
      minHeight: invariantMinHeight(true),
    })
    expect(shrunk[0]).toMatchObject({ width: 320, height: 303 }) // never below the content floor
  })

  it('resizeBy respects the host-derived maximum', () => {
    const ws = [win('a', { width: 400, height: 400 })]
    const grown = resizeBy(ws, 'a', 10_000, 10_000, { maxWidth: 800, maxHeight: 600 })
    expect(grown[0]).toMatchObject({ width: 800, height: 600 })
  })
})

describe('WindowReducer — CYP-26 keep-in-viewport clamp', () => {
  it('keeps at least keepVisible dp on every edge; title bar stays reachable (y >= 0)', () => {
    const host = { w: 1000, h: 800 }
    // dragged far off top-left
    const tl = clampToBounds(win('a', { x: -5000, y: -5000, width: 400, height: 300 }), host.w, host.h)
    expect(tl.y).toBe(0) // top edge never above 0
    expect(tl.x).toBe(48 - 400) // minX = keepVisible - width → 48 px of the window stays on screen
    // dragged far off bottom-right
    const br = clampToBounds(win('a', { x: 5000, y: 5000, width: 400, height: 300 }), host.w, host.h)
    expect(br.x).toBe(1000 - 48) // maxX = host - keepVisible
    expect(br.y).toBe(800 - 48)
  })

  it('is a no-op until the host is measured', () => {
    const w = win('a', { x: -5000, y: -5000 })
    expect(clampToBounds(w, 0, 0)).toEqual(w)
  })
})

describe('WindowReducer — clampSizeToBounds', () => {
  it('shrinks an oversized window back within the host, never below the minimum', () => {
    const clamped = clampSizeToBounds(win('a', { width: 5000, height: 5000 }), 1000, 800)
    expect(clamped).toMatchObject({ width: 1000, height: 800 })
    const tiny = clampSizeToBounds(win('a', { width: 5000, height: 5000 }), 100, 90, 160, 120)
    expect(tiny).toMatchObject({ width: 160, height: 120 }) // min wins when host < min
  })
})
