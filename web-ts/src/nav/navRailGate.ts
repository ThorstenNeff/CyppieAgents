// CYP-888 (NR-1) — the responsive gate for the vertical nav-rail. Pure + deterministic so the gate rule itself is the
// tooth target (the hook that reads the live viewport is a thin wrapper over this). Spec (PL, identical to Team-1 desktop
// so the clients don't drift): the rail is shown ONLY in landscape with the short edge ≥ ~600dp (tablet+). Portrait, or
// any smaller short edge, → NO rail; the current canvas renders unchanged.
export const NAV_RAIL_MIN_SHORT_EDGE_DP = 600

export interface Viewport {
  width: number
  height: number
}

/**
 * Whether the vertical nav-rail should be shown for this viewport. Landscape = width strictly greater than height (a
 * square viewport is NOT landscape → no rail). Short edge = the smaller of the two dimensions; in landscape that is the
 * height. Both conditions must hold: landscape AND short-edge ≥ NAV_RAIL_MIN_SHORT_EDGE_DP.
 */
export function shouldShowNavRail(v: Viewport): boolean {
  const landscape = v.width > v.height
  const shortEdge = Math.min(v.width, v.height)
  return landscape && shortEdge >= NAV_RAIL_MIN_SHORT_EDGE_DP
}
