import { describe, it, expect } from 'vitest'
import { senderAccent } from './senderAccent'

const WORKER_ACCENTS = ['#5FD0BE', '#B9A4F2', '#E693D2', '#8FAAEF', '#D9AE6E', '#F0A0B3', '#9EB8D6', '#BFC97E']

describe('senderAccent (CYP-407)', () => {
  it('the PO/hub gets the reserved accent (by role or by id)', () => {
    expect(senderAccent('po')).toBe('#A6A9F0')
    expect(senderAccent('whoever', 'PO')).toBe('#A6A9F0')
  })

  it('is deterministic and stable for a given id', () => {
    expect(senderAccent('frontend')).toBe(senderAccent('frontend'))
  })

  it('non-PO ids map into the worker palette, never the PO accent', () => {
    expect(senderAccent('frontend')).not.toBe('#A6A9F0')
    expect(WORKER_ACCENTS).toContain(senderAccent('frontend'))
    expect(WORKER_ACCENTS).toContain(senderAccent('backend'))
  })
})
