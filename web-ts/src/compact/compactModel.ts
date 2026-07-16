// CYP-649 (P6, Epic CYP-640) — the pure compact-orchestration policy. Parity with CMP CompactPanel/CompactViewModel
// (CYP-326/327/328/329). Framework-free + unit-tested; the honesty rules (unknown ≠ idle/off, fail-closed allowed)
// live here.
//
// BOUNDS: replicated from the Kotlin single-source `CompactConfig` (core/…/CompactModel.kt). These are UX-only —
// the SERVER `timingBoundsError()` is the authority and rejects out-of-range with 400 (fail-closed) regardless, so a
// drift here can only show a stale inline hint, never admit a bad value. (Consistent with restRepo's hand-modeled
// REST DTOs — the generated contract carries no constants. FLAG: a future contract export would remove the drift.)
import type { CompactStatus, CompactRunSummary } from '../types/generated/contract'

export const COMPACT_BOUNDS = {
  thresholdTokens: { min: 1, max: 1_000_000 }, // ~1M formatCompactTokens window
  staggerMs: { min: 30_000, max: 600_000 }, //   30 s .. 10 min
  roundGapMs: { min: 30_000, max: 1_800_000 }, // 30 s .. 30 min
  roundWindowMs: { min: 60_000, max: 1_800_000 }, // 60 s .. 30 min
} as const

export type CompactField = keyof typeof COMPACT_BOUNDS

/** True iff `n` is an integer within the field's inclusive bounds. */
export function inBounds(field: CompactField, n: number): boolean {
  const { min, max } = COMPACT_BOUNDS[field]
  return Number.isFinite(n) && Number.isInteger(n) && n >= min && n <= max
}

/** The honest status kind. `null` status → 'unknown' (facts render ABSENT, never a defaulted idle/off). Fail-closed:
 *  an un-allowed hub is 'off' even if some stale run flag lingers. */
export type CompactStatusKind = 'unknown' | 'off' | 'running' | 'idle'
export function compactStatusKind(status: CompactStatus | null | undefined): CompactStatusKind {
  if (status == null) return 'unknown'
  if (!status.allowed) return 'off'
  if (status.running) return 'running'
  return 'idle'
}

/** The last-run outcome. Never green-washes: an unfinished run is 'running', a not-all-completed finish is 'timeout'
 *  (the machine window elapsed), an explicit abort is 'aborted', all-completed is 'ok'. */
export type CompactRunOutcome = 'running' | 'ok' | 'timeout' | 'aborted'
export function lastRunOutcome(run: CompactRunSummary): CompactRunOutcome {
  if (run.finishedTs == null) return 'running'
  if (run.aborted === true) return 'aborted'
  return run.completed >= run.total ? 'ok' : 'timeout'
}

/** Compact token count for the threshold preview (K/M, matching CMP formatCompactTokens; truncated, never overstates). */
export function formatCompactTokens(n: number): string {
  if (n < 1_000) return String(n)
  if (n < 1_000_000) return `${Math.trunc(n / 1_000)}K`
  const tenths = Math.trunc(n / 100_000)
  return `${Math.trunc(tenths / 10)}.${tenths % 10}M`
}

/** Human duration for a timing preview: whole minutes → "N min", whole seconds → "N s", else raw "N ms". */
export function formatDuration(ms: number): string {
  if (ms % 60_000 === 0) return `${ms / 60_000} min`
  if (ms % 1_000 === 0) return `${ms / 1_000} s`
  return `${ms} ms`
}
