// CYP-402 (W4) — the window-manager store (Zustand, Spec 14 §2). Thin reactive shell over the pure WindowReducer:
// all geometry math lives in windowReducer.ts (tested there); the store wires actions to it and applies the
// CYP-26 keep-in-viewport clamp on move and the host-shrink clamp on resize/host-change.
import { create } from 'zustand'
import type { WindowState } from './windowState'
import * as R from './windowReducer'

export interface WindowStore {
  /** List order IS the z-order: last = top-most (focused). */
  windows: WindowState[]
  /** CYP-664: the STABLE registration order (order in which windows were added), NEVER reordered by focus/move/resize
   *  — parity with CMP's `windowOrder`. The desktop uses `windows` (z-order); the phone-pager keys its pages off this
   *  so giving a window focus moves only the CURRENT page, never re-sorts the pages. Maintained ONLY in add/remove. */
  windowOrder: string[]
  host: { width: number; height: number }
  /** content windows (Agent/Comm) carry a composer → the larger resize floor (CYP-373). */
  contentIds: ReadonlySet<string>

  setHost: (width: number, height: number) => void
  add: (win: WindowState, isContent?: boolean) => void
  remove: (id: string) => void
  focus: (id: string) => void
  moveBy: (id: string, dx: number, dy: number) => void
  resizeBy: (id: string, dWidth: number, dHeight: number) => void
}

export const useWindowStore = create<WindowStore>((set) => ({
  windows: [],
  windowOrder: [],
  host: { width: 0, height: 0 },
  contentIds: new Set<string>(),

  setHost: (width, height) =>
    set((s) => ({
      host: { width, height },
      // A shrunk host snaps oversized windows back in and re-clamps positions (CYP-16/CYP-26).
      windows: s.windows.map((w) => R.clampToBounds(R.clampSizeToBounds(w, width, height), width, height)),
    })),

  add: (win, isContent = false) =>
    set((s) => ({
      windows: [...s.windows, win],
      // CYP-664: append to the stable registration order (dedupe — an idempotent re-add never double-registers).
      windowOrder: s.windowOrder.includes(win.id) ? s.windowOrder : [...s.windowOrder, win.id],
      contentIds: isContent ? new Set([...s.contentIds, win.id]) : s.contentIds,
    })),

  remove: (id) =>
    set((s) => {
      const contentIds = new Set(s.contentIds)
      contentIds.delete(id)
      return { windows: s.windows.filter((w) => w.id !== id), windowOrder: s.windowOrder.filter((wid) => wid !== id), contentIds }
    }),

  // CYP-664: focus reorders the Z-ORDER only (windows) — windowOrder is NOT returned here, so it stays put (the pager
  // pages never re-sort on focus). Same for moveBy/resizeBy/setHost below.
  focus: (id) => set((s) => ({ windows: R.bringToFront(s.windows, id) })),

  moveBy: (id, dx, dy) =>
    set((s) => ({
      windows: s.windows.map((w) =>
        w.id === id ? R.clampToBounds({ ...w, x: w.x + dx, y: w.y + dy }, s.host.width, s.host.height) : w,
      ),
    })),

  resizeBy: (id, dWidth, dHeight) =>
    set((s) => {
      const isContent = s.contentIds.has(id)
      return {
        windows: R.resizeBy(s.windows, id, dWidth, dHeight, {
          minWidth: R.invariantMinWidth(isContent),
          minHeight: R.invariantMinHeight(isContent),
          maxWidth: s.host.width > 0 ? s.host.width : Number.POSITIVE_INFINITY,
          maxHeight: s.host.height > 0 ? s.host.height : Number.POSITIVE_INFINITY,
        }),
      }
    }),
}))
