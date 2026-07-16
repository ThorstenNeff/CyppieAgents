// CYP-642 — the pure capacity/overload policy. Teeth pin: null≠0/0 (absent, not "0/0"), null-max → "N aktiv",
// full only at current==max, and the banner self-clear + un-dismiss rules. Reddening mutations noted per block.
import { describe, it, expect } from 'vitest'
import { capacityReadout, isFull, overloadVisible } from './capacityModel'

describe('capacityReadout', () => {
  it('null/undefined capacity → absent (never "0/0" — H1/Q3)', () => {
    // RED if a nullish snapshot ever produces a headroom/nomax readout → a phantom "0/0" pill.
    expect(capacityReadout(null)).toEqual({ kind: 'absent' })
    expect(capacityReadout(undefined)).toEqual({ kind: 'absent' })
  })

  it('null estimatedMax → nomax "N aktiv" (max not estimated yet, still neutral)', () => {
    expect(capacityReadout({ current: 3, estimatedMax: null })).toEqual({ kind: 'nomax', current: 3 })
    expect(capacityReadout({ current: 0, estimatedMax: undefined })).toEqual({ kind: 'nomax', current: 0 })
  })

  it('current < estimatedMax → headroom (neutral, NOT green/amber-early)', () => {
    expect(capacityReadout({ current: 2, estimatedMax: 6 })).toEqual({ kind: 'headroom', current: 2, max: 6 })
  })

  it('current == estimatedMax → full (the WARN-amber state)', () => {
    // RED if full triggers early (< max) or never (>= mutated to >) — the boundary is exactly current==max.
    expect(capacityReadout({ current: 6, estimatedMax: 6 })).toEqual({ kind: 'full', current: 6, max: 6 })
    expect(capacityReadout({ current: 7, estimatedMax: 6 })).toEqual({ kind: 'full', current: 7, max: 6 })
  })
})

describe('isFull', () => {
  it('true only when a finite non-negative max is reached', () => {
    expect(isFull({ current: 6, estimatedMax: 6 })).toBe(true)
    expect(isFull({ current: 5, estimatedMax: 6 })).toBe(false)
    expect(isFull({ current: 5, estimatedMax: null })).toBe(false) // no max → never full
  })
})

describe('overloadVisible (Q5 — real reject, self-clear, dismiss)', () => {
  const full = { current: 6, estimatedMax: 6 }
  const room = { current: 2, estimatedMax: 6 }

  it('hidden when not active', () => {
    expect(overloadVisible(false, false, full)).toBe(false)
  })
  it('visible when active, not dismissed, and still full', () => {
    expect(overloadVisible(true, false, full)).toBe(true)
  })
  it('hidden when dismissed even though still active', () => {
    expect(overloadVisible(true, true, full)).toBe(false)
  })
  it('self-clears when headroom returns (active but capacity no longer full)', () => {
    // RED if the banner survives once capacity has room again — the machine limit is no longer reached.
    expect(overloadVisible(true, false, room)).toBe(false)
  })
  it('unknown capacity does NOT self-clear a live reject (can\'t prove headroom returned)', () => {
    expect(overloadVisible(true, false, null)).toBe(true)
  })
})
