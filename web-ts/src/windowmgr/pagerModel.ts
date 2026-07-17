// CYP-664 (P12, Epic CYP-640) — the pure honesty core of the Phone-Pager responsive layout, port of the CMP
// WindowManager PhonePager/PagerIndicator (CYP-54). Framework-free + unit-tested; the components draw these.
//
// At a COMPACT window-size-class (phone/narrow) the desktop's floating windows become a one-window-per-page pager
// with an honest indicator; at desktop size the existing floating window-manager stays. The switch is presentation-
// only — the SAME window models render either way, so no window/focus/input state is lost.
//
// Honesty invariants:
//  - The pager derives entirely from the REAL window list — pages.length === window count, no phantom page/dot.
//  - The current page is always a REAL index (clamped into range), never a position beyond the window count.
//  - Pages key off a STABLE order (the caller supplies it) so giving a window focus never re-sorts the pages (CMP
//    keeps a separate windowOrder for exactly this); focus only moves the CURRENT page, not the page order.

// ── Size class ───────────────────────────────────────────────────────────────────────────────────────────────────
/** Compact width upper bound (Material WindowWidthSizeClass.Compact = <600dp; mirrors web-ts eventBrowse
 *  PANE_COLLAPSE_WIDTH + :app:shared LayoutBreakpoints.PANE_COLLAPSE_WIDTH). */
export const COMPACT_MAX_WIDTH = 600
/** Compact height upper bound (Material WindowHeightSizeClass.Compact = <480dp — a wide-but-short landscape phone). */
export const COMPACT_MAX_HEIGHT = 480

/** Compact ⇔ EITHER axis is in the Material Compact band (phone portrait, OR wide-but-short landscape phone) — the
 *  same rule as CMP's `widthSizeClass==Compact || heightSizeClass==Compact`. An UNMEASURED dimension (0) is treated
 *  as desktop: never collapse to the pager before we actually know the size (the eventBrowse `isSinglePane` guard). */
export function isCompact(width: number, height: number): boolean {
  if (width <= 0 || height <= 0) return false
  return width < COMPACT_MAX_WIDTH || height < COMPACT_MAX_HEIGHT
}

// ── Pager view ───────────────────────────────────────────────────────────────────────────────────────────────────
/** ≤ this many pages → tappable dots; beyond → a compact "N / M" counter (CMP PAGER_DOT_THRESHOLD). */
export const PAGER_DOT_THRESHOLD = 6

export interface PagerPage {
  id: string
  title: string
}

export interface PagerView {
  /** One page per window, in the STABLE order given by the caller (never re-sorted by focus). */
  pages: PagerPage[]
  /** The current page index — a REAL index in [0, pages.length-1], or -1 when there are no windows. Clamped: a
   *  focusedId not in the list, or out-of-range, falls back to the first page (never a phantom position). */
  currentIndex: number
  /** The indicator shows only with more than one page (a single window needs no pager chrome). */
  showIndicator: boolean
  /** Dots vs the "N / M" counter — dots only up to the threshold, so the row never overflows on a narrow phone. */
  useDots: boolean
}

/** Derive the pager presentation from the REAL, stably-ordered window list + the current focus anchor. Pure: same
 *  window list → same view; the count and current index are always honest (no phantom page/dot). */
export function pagerView(ordered: readonly PagerPage[], focusedId: string | null): PagerView {
  const pages = ordered.map((p) => ({ id: p.id, title: p.title }))
  const n = pages.length
  let currentIndex: number
  if (n === 0) {
    currentIndex = -1
  } else {
    const found = focusedId === null ? -1 : pages.findIndex((p) => p.id === focusedId)
    // clamp: an absent/unknown focus falls back to the first REAL page — never an index past the window count.
    currentIndex = found >= 0 ? found : 0
  }
  return {
    pages,
    currentIndex,
    showIndicator: n > 1,
    useDots: n <= PAGER_DOT_THRESHOLD,
  }
}

/** The honest "N / M" counter label for the over-threshold case (1-based page number / real total). Empty → "0 / 0". */
export function pagerCounterLabel(view: PagerView): string {
  if (view.pages.length === 0) return '0 / 0'
  return `${view.currentIndex + 1} / ${view.pages.length}`
}

export const PHONE_PAGER_TESTID = {
  pager: 'phonePager.pager',
  empty: 'phonePager.empty',
  header: 'phonePager.header',
  page: (id: string) => `phonePager.page.${id}`,
  indicator: 'phonePager.indicator',
  counter: 'phonePager.indicator.counter',
  dot: (id: string) => `phonePager.indicator.dot.${id}`,
  prev: 'phonePager.prev',
  next: 'phonePager.next',
} as const
