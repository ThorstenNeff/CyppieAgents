import { describe, it, expect } from 'vitest'
import { ComposerRecall } from './composerRecall'

const H = ['a', 'b', 'c'] // newest last

describe('ComposerRecall (CYP-387 §2)', () => {
  it('↑ enters at the newest (stashing the draft), steps older, and stops at the oldest (consumed, not null)', () => {
    const r = new ComposerRecall()
    expect(r.older(H, 'live')).toBe('c')
    expect(r.older(H, 'live')).toBe('b')
    expect(r.older(H, 'live')).toBe('a')
    expect(r.older(H, 'live')).toBe('live') // at oldest: no move, returns the draft (consumed)
  })

  it('↓ steps newer and restores the stashed draft past the newest', () => {
    const r = new ComposerRecall()
    r.older(H, 'live') // 'c', stash = 'live'
    r.older(H, 'live') // 'b'
    expect(r.newer(H, 'x')).toBe('c')
    expect(r.newer(H, 'x')).toBe('live') // past the newest → the stashed live draft
    expect(r.index()).toBeNull()
  })

  it('↑ on empty history is not consumed (null); ↓ at the live draft is not consumed (null)', () => {
    const r = new ComposerRecall()
    expect(r.older([], 'live')).toBeNull()
    expect(r.newer(H, 'live')).toBeNull()
  })

  it('history is immutable: a recall-EDIT does not change what the next ↑ reads (spec test 3)', () => {
    const r = new ComposerRecall()
    expect(r.older(H, '')).toBe('c') // enter at newest
    // caller edited the recalled draft to 'c-EDITED'; the next ↑ still reads the ORIGINAL neighbour 'b'
    expect(r.older(H, 'c-EDITED')).toBe('b')
  })
})
