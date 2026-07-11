// CYP-402 (W4) — pure, side-effect-free transforms over the window list, ported from :app:shared
// (window/WindowManagerState.kt `WindowReducer`). List order encodes z-order: last element = top-most (focused).
// These are the invariant-bearing core (focus, drag, resize floors, CYP-26 keep-in-viewport clamp); the DOM
// window components (pointer drag/resize, chrome) sit on top and call these.
import {
  type WindowState,
  MIN_WINDOW_WIDTH,
  MIN_WINDOW_HEIGHT,
  TILED_CONTENT_WINDOW_MIN_WIDTH,
  CONTENT_WINDOW_MIN_HEIGHT,
  MIN_VISIBLE_WINDOW,
} from './windowState'

const clamp = (v: number, lo: number, hi: number): number => Math.min(Math.max(v, lo), hi)

/** CYP-338 — height floor by window class: content windows (Agent/Comm) keep their composer. */
export function invariantMinHeight(isContent: boolean): number {
  return isContent ? CONTENT_WINDOW_MIN_HEIGHT : MIN_WINDOW_HEIGHT
}

/** CYP-373 — width floor by window class (the twin of [invariantMinHeight]); a content window never sinks to 160. */
export function invariantMinWidth(isContent: boolean): number {
  return isContent ? TILED_CONTENT_WINDOW_MIN_WIDTH : MIN_WINDOW_WIDTH
}

/** Move the window with [id] to the front (end) of the stack. No-op if absent or already front. */
export function bringToFront(windows: readonly WindowState[], id: string): WindowState[] {
  const index = windows.findIndex((w) => w.id === id)
  if (index < 0 || index === windows.length - 1) return [...windows]
  const next = [...windows]
  const [win] = next.splice(index, 1)
  next.push(win)
  return next
}

/** Translate only the window with [id] by ([dx], [dy]) dp. */
export function moveBy(windows: readonly WindowState[], id: string, dx: number, dy: number): WindowState[] {
  return windows.map((w) => (w.id === id ? { ...w, x: w.x + dx, y: w.y + dy } : w))
}

export interface ResizeBounds {
  minWidth?: number
  minHeight?: number
  maxWidth?: number
  maxHeight?: number
}

/** Grow/shrink only the window with [id] by ([dWidth], [dHeight]) dp, clamped into the min/max box. */
export function resizeBy(
  windows: readonly WindowState[],
  id: string,
  dWidth: number,
  dHeight: number,
  bounds: ResizeBounds = {},
): WindowState[] {
  const minWidth = bounds.minWidth ?? MIN_WINDOW_WIDTH
  const minHeight = bounds.minHeight ?? MIN_WINDOW_HEIGHT
  const maxWidth = bounds.maxWidth ?? Number.POSITIVE_INFINITY
  const maxHeight = bounds.maxHeight ?? Number.POSITIVE_INFINITY
  return windows.map((w) =>
    w.id === id
      ? {
          ...w,
          width: clamp(w.width + dWidth, minWidth, Math.max(minWidth, maxWidth)),
          height: clamp(w.height + dHeight, minHeight, Math.max(minHeight, maxHeight)),
        }
      : w,
  )
}

/** Mirror the horizontal resize delta for RTL (the grip is at the visual bottom-end corner). */
export function resizeDeltaForLayout(dragX: number, isRtl: boolean): number {
  return isRtl ? -dragX : dragX
}

/**
 * CYP-26 — clamp a window's top-left so at least [keepVisible] dp stays inside the host on every edge (never
 * fully off-screen). The top edge is kept at or below 0 so the draggable title bar stays reachable. No-op until
 * the host is measured (host <= 0).
 */
export function clampToBounds(
  window: WindowState,
  hostWidth: number,
  hostHeight: number,
  keepVisible: number = MIN_VISIBLE_WINDOW,
): WindowState {
  if (hostWidth <= 0 || hostHeight <= 0) return window
  const minX = keepVisible - window.width
  const maxX = Math.max(hostWidth - keepVisible, minX)
  const minY = 0
  const maxY = Math.max(hostHeight - keepVisible, minY)
  return { ...window, x: clamp(window.x, minX, maxX), y: clamp(window.y, minY, maxY) }
}

/** Shrink a window so it is never larger than the host (never below the minimum). No-op until host measured. */
export function clampSizeToBounds(
  window: WindowState,
  hostWidth: number,
  hostHeight: number,
  minWidth: number = MIN_WINDOW_WIDTH,
  minHeight: number = MIN_WINDOW_HEIGHT,
): WindowState {
  if (hostWidth <= 0 || hostHeight <= 0) return window
  return {
    ...window,
    width: clamp(window.width, minWidth, Math.max(minWidth, hostWidth)),
    height: clamp(window.height, minHeight, Math.max(minHeight, hostHeight)),
  }
}
