// CYP-649 — the pure compact policy. Teeth pin: fail-closed status (unknown/off never idle-washed), the last-run
// outcome (never green-washes a timeout/abort), bounds, and the formatters. Reddening mutations noted per block.
import { describe, it, expect } from 'vitest'
import {
  COMPACT_BOUNDS,
  inBounds,
  compactStatusKind,
  lastRunOutcome,
  formatCompactTokens,
  formatDuration,
} from './compactModel'
import type { CompactStatus, CompactRunSummary } from '../types/generated/contract'

const status = (o: Partial<CompactStatus>): CompactStatus => ({ allowed: true, thresholdTokens: 500_000, armed: false, running: false, ...o })
const run = (o: Partial<CompactRunSummary>): CompactRunSummary => ({ completed: 0, total: 3, startedTs: 1000, ...o })

describe('compactStatusKind (fail-closed honesty)', () => {
  it('null → unknown (facts render absent, never a defaulted idle/off)', () => {
    expect(compactStatusKind(null)).toBe('unknown')
    expect(compactStatusKind(undefined)).toBe('unknown')
  })
  it('not allowed → off (even if a stale run flag lingers)', () => {
    // RED if !allowed ever reads as idle/running — an un-allowed hub is OFF.
    expect(compactStatusKind(status({ allowed: false, running: true }))).toBe('off')
  })
  it('allowed + running → running; allowed + not running → idle', () => {
    expect(compactStatusKind(status({ allowed: true, running: true }))).toBe('running')
    expect(compactStatusKind(status({ allowed: true, running: false }))).toBe('idle')
  })
})

describe('lastRunOutcome (never green-washes)', () => {
  it('unfinished → running', () => {
    expect(lastRunOutcome(run({ finishedTs: null }))).toBe('running')
  })
  it('finished, not all completed → timeout (the window elapsed)', () => {
    // RED if a partial completion reads as ok — completed < total after finish is a timeout.
    expect(lastRunOutcome(run({ finishedTs: 2000, completed: 2, total: 3 }))).toBe('timeout')
  })
  it('finished, all completed → ok', () => {
    expect(lastRunOutcome(run({ finishedTs: 2000, completed: 3, total: 3 }))).toBe('ok')
  })
  it('explicit abort → aborted (dominates the count)', () => {
    expect(lastRunOutcome(run({ finishedTs: 2000, completed: 3, total: 3, aborted: true }))).toBe('aborted')
  })
})

describe('inBounds', () => {
  it('respects each field inclusive bounds; rejects non-integer / out-of-range', () => {
    expect(inBounds('thresholdTokens', 1)).toBe(true)
    expect(inBounds('thresholdTokens', 1_000_000)).toBe(true)
    expect(inBounds('thresholdTokens', 0)).toBe(false)
    expect(inBounds('thresholdTokens', 1_000_001)).toBe(false)
    expect(inBounds('staggerMs', COMPACT_BOUNDS.staggerMs.min)).toBe(true)
    expect(inBounds('staggerMs', COMPACT_BOUNDS.staggerMs.min - 1)).toBe(false)
    expect(inBounds('thresholdTokens', 1.5)).toBe(false)
    expect(inBounds('thresholdTokens', Number.NaN)).toBe(false)
  })
})

describe('formatters', () => {
  it('formatCompactTokens: K/M, truncated (never overstates)', () => {
    expect(formatCompactTokens(500)).toBe('500')
    expect(formatCompactTokens(500_000)).toBe('500K')
    expect(formatCompactTokens(1_250_000)).toBe('1.2M')
  })
  it('formatDuration: whole min → "N min", whole sec → "N s"', () => {
    expect(formatDuration(30_000)).toBe('30 s')
    expect(formatDuration(120_000)).toBe('2 min')
    expect(formatDuration(600_000)).toBe('10 min')
  })
})
