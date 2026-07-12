import { describe, it, expect } from 'vitest'
import { hasLoginFlowReturn } from './authConfig'

describe('hasLoginFlowReturn (CYP-515 — Kratos `?flow=` return marker)', () => {
  it('true when a non-empty flow id is present (anywhere in the query)', () => {
    expect(hasLoginFlowReturn('?flow=abc123')).toBe(true)
    expect(hasLoginFlowReturn('?foo=1&flow=xyz')).toBe(true)
  })

  it('false for no query / other params / present-but-empty flow → the normal login redirect still stands', () => {
    expect(hasLoginFlowReturn('')).toBe(false)
    expect(hasLoginFlowReturn('?foo=bar')).toBe(false)
    expect(hasLoginFlowReturn('?flow=')).toBe(false) // empty is not a real flow return
  })
})
