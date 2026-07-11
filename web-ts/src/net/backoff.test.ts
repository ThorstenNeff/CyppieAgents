import { describe, it, expect } from 'vitest'
import { Backoff } from './backoff'

describe('Backoff', () => {
  it('grows exponentially and caps at maxMs', () => {
    const b = new Backoff({ initialMs: 100, maxMs: 1000, factor: 2 })
    expect([b.next(), b.next(), b.next(), b.next(), b.next(), b.next()]).toEqual([100, 200, 400, 800, 1000, 1000])
  })

  it('reset returns to the initial delay after a successful connect', () => {
    const b = new Backoff({ initialMs: 100, maxMs: 1000, factor: 2 })
    b.next()
    b.next()
    b.reset()
    expect(b.next()).toBe(100)
  })
})
