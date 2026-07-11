// CYP-402 (W4) — window-manager state + floors, ported from :app:shared (window/WindowManagerState.kt). List
// order IS the z-order: index 0 is bottom-most, the LAST element is the top-most (focused) window.
export interface WindowState {
  id: string
  title: string
  x: number
  y: number
  width: number
  height: number
}

/** Smallest width any window may resize to (dp). Hard floor for non-content windows (ACL/Event-Log). */
export const MIN_WINDOW_WIDTH = 160
/** Smallest height for non-content windows (dp). */
export const MIN_WINDOW_HEIGHT = 120
/** Min width for content windows (Agent/Comm) so the composer + send stay usable (CYP-26/CYP-373). */
export const TILED_CONTENT_WINDOW_MIN_WIDTH = 320
/** Min height for content windows (Agent/Comm) — chrome + 3 transcript lines (CYP-338/363). */
export const CONTENT_WINDOW_MIN_HEIGHT = 303
/** CYP-26 clamp: at least this many dp of a window stays inside the host on every edge (never fully off-screen). */
export const MIN_VISIBLE_WINDOW = 48
