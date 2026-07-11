import { describe, it, expect } from 'vitest'
import { isNearBottom, TRANSCRIPT_BOTTOM_TOLERANCE_PX } from './autoscrollPin'

// distance from bottom = scrollHeight - scrollTop - clientHeight
describe('isNearBottom (CYP-404 pin predicate)', () => {
  it('exact bottom counts as near-bottom', () => {
    expect(isNearBottom(900, 1000, 100)).toBe(true) // distance 0
  })

  it('within the tolerance is near-bottom; just past it is not', () => {
    expect(isNearBottom(852, 1000, 100)).toBe(true) // distance 48 == tolerance
    expect(isNearBottom(851, 1000, 100)).toBe(false) // distance 49 > 48
  })

  it('short content (nothing to scroll) is at the bottom', () => {
    expect(isNearBottom(0, 50, 100)).toBe(true) // negative distance
  })

  it('THE tolerance guard: a small gap still counts as bottom, but zero tolerance would flicker the pin off', () => {
    const scrollTop = 860 // distance 40
    expect(isNearBottom(scrollTop, 1000, 100, 48)).toBe(true)
    expect(isNearBottom(scrollTop, 1000, 100, 0)).toBe(false) // exactly the flicker the tolerance prevents (CYP-393)
  })

  it('default tolerance is 48px', () => {
    expect(TRANSCRIPT_BOTTOM_TOLERANCE_PX).toBe(48)
  })
})
