import { describe, it, expect } from 'vitest'
import { InputHistory } from './inputHistory'

describe('InputHistory (CYP-387 parity)', () => {
  it('is off when N <= 0: record no-ops, entries empty', () => {
    const off = new InputHistory(0)
    off.record('a')
    expect(off.entries).toEqual([])
    const neg = new InputHistory(-5)
    neg.record('a')
    expect(neg.entries).toEqual([])
  })

  it('keeps newest-last and evicts the oldest at N', () => {
    const h = new InputHistory(2)
    h.record('a')
    h.record('b')
    h.record('c')
    expect(h.entries).toEqual(['b', 'c'])
  })

  it('does not dedup consecutive duplicates (v1)', () => {
    const h = new InputHistory(5)
    h.record('x')
    h.record('x')
    expect(h.entries).toEqual(['x', 'x'])
  })

  it('never records blank; trims what it records', () => {
    const h = new InputHistory(5)
    h.record('   ')
    h.record('')
    expect(h.entries).toEqual([])
    h.record('  hi  ')
    expect(h.entries).toEqual(['hi'])
  })

  it('reflects a live capacity shrink on read (before the next send)', () => {
    let n = 5
    const h = new InputHistory(() => n)
    h.record('a')
    h.record('b')
    h.record('c')
    n = 2
    expect(h.entries).toEqual(['b', 'c'])
    n = 0
    expect(h.entries).toEqual([])
  })
})
