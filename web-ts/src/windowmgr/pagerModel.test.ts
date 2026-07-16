// CYP-664 — teeth for the Phone-Pager honesty core: the compact size-class threshold (+ 0-unmeasured=desktop guard),
// the honest pager view (pages === real windows, current index clamped to a real page, indicator only when >1), and
// the dot-vs-counter threshold.
import { describe, it, expect } from 'vitest'
import {
  isCompact,
  COMPACT_MAX_WIDTH,
  COMPACT_MAX_HEIGHT,
  pagerView,
  pagerCounterLabel,
  PAGER_DOT_THRESHOLD,
  type PagerPage,
} from './pagerModel'

const pages = (...ids: string[]): PagerPage[] => ids.map((id) => ({ id, title: id.toUpperCase() }))

describe('isCompact (size class)', () => {
  it('compact when EITHER axis is below its bound', () => {
    expect(isCompact(400, 800)).toBe(true) // narrow width
    expect(isCompact(1200, 400)).toBe(true) // short height (landscape phone)
    expect(isCompact(COMPACT_MAX_WIDTH - 1, 800)).toBe(true)
    expect(isCompact(1200, COMPACT_MAX_HEIGHT - 1)).toBe(true)
  })
  it('desktop when BOTH axes are at/above their bounds', () => {
    expect(isCompact(COMPACT_MAX_WIDTH, COMPACT_MAX_HEIGHT)).toBe(false)
    expect(isCompact(1200, 800)).toBe(false)
  })
  it('an UNMEASURED dimension (0) is desktop — never collapse before we know the size', () => {
    // RED if the 0-guard is dropped: a 0-width mount would flash the pager on desktop.
    expect(isCompact(0, 800)).toBe(false)
    expect(isCompact(400, 0)).toBe(false)
    expect(isCompact(0, 0)).toBe(false)
  })
})

describe('pagerView (honest derivation)', () => {
  it('one page per REAL window, in the given stable order', () => {
    const v = pagerView(pages('comm', 'acl', 'agent:po'), null)
    expect(v.pages.map((p) => p.id)).toEqual(['comm', 'acl', 'agent:po'])
    expect(v.pages.length).toBe(3) // count === real windows, no phantom
  })
  it('current index is the focused page — a REAL index', () => {
    const v = pagerView(pages('comm', 'acl', 'agent:po'), 'acl')
    expect(v.currentIndex).toBe(1)
  })
  it('an unknown/absent focus clamps to the first real page — never a phantom position', () => {
    // RED if an unknown focus is allowed through as -1/out-of-range instead of clamping to 0.
    expect(pagerView(pages('comm', 'acl'), 'ghost').currentIndex).toBe(0)
    expect(pagerView(pages('comm', 'acl'), null).currentIndex).toBe(0)
  })
  it('empty → no pages, currentIndex -1, no indicator', () => {
    const v = pagerView(pages(), null)
    expect(v.pages).toEqual([])
    expect(v.currentIndex).toBe(-1)
    expect(v.showIndicator).toBe(false)
  })
  it('indicator only with MORE than one page', () => {
    expect(pagerView(pages('comm'), 'comm').showIndicator).toBe(false)
    expect(pagerView(pages('comm', 'acl'), 'comm').showIndicator).toBe(true)
  })
  it('dots up to the threshold, counter beyond', () => {
    const ids = Array.from({ length: PAGER_DOT_THRESHOLD }, (_, i) => `w${i}`)
    expect(pagerView(pages(...ids), 'w0').useDots).toBe(true) // exactly the threshold → dots
    expect(pagerView(pages(...ids, 'wX'), 'w0').useDots).toBe(false) // one over → counter
  })
})

describe('pagerCounterLabel', () => {
  it('is the honest 1-based page / real total', () => {
    expect(pagerCounterLabel(pagerView(pages('a', 'b', 'c'), 'b'))).toBe('2 / 3')
    expect(pagerCounterLabel(pagerView(pages(), null))).toBe('0 / 0')
  })
})
