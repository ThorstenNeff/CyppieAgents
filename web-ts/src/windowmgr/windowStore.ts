// CYP-402 (W4) — the window-manager store (Zustand, Spec 14 §2). Thin reactive shell over the pure WindowReducer:
// all geometry math lives in windowReducer.ts (tested there); the store wires actions to it and applies the
// CYP-26 keep-in-viewport clamp on move and the host-shrink clamp on resize/host-change.
import { create } from 'zustand'
import type { WindowState } from './windowState'
import * as R from './windowReducer'

export interface WindowStore {
  /** List order IS the z-order: last = top-most (focused). */
  windows: WindowState[]
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
      contentIds: isContent ? new Set([...s.contentIds, win.id]) : s.contentIds,
    })),

  remove: (id) =>
    set((s) => {
      const contentIds = new Set(s.contentIds)
      contentIds.delete(id)
      return { windows: s.windows.filter((w) => w.id !== id), contentIds }
    }),

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
