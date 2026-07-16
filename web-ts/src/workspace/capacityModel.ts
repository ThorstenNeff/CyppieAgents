// CYP-642 (P2, Epic CYP-640) — the pure hub-capacity/overload policy. Parity with the CMP CapacityReadout +
// OverloadBanner (CYP-417). Framework-free + unit-tested; the honesty rules (null ≠ 0, estimate ≠ SLA, banner only
// on a real server reject) live here where they can be proven.
import type { Capacity } from '../types/generated/contract'

/** `current == estimatedMax` is "full"; a null estimatedMax is NEVER full (the max isn't estimated yet). Guards
 *  against a non-finite/negative estimate (treat as "no max"). */
export function isFull(cap: Capacity): boolean {
  const max = cap.estimatedMax
  return typeof max === 'number' && Number.isFinite(max) && max >= 0 && cap.current >= max
}

export type CapacityReadout =
  | { kind: 'absent' } // no capacity data at all (pre-GET / unauthenticated) → render NOTHING (H1/Q3, never "0/0")
  | { kind: 'nomax'; current: number } // max not yet estimated → "N aktiv", neutral
  | { kind: 'headroom'; current: number; max: number } // known max, room → "N/M", neutral (NOT green/amber-early)
  | { kind: 'full'; current: number; max: number } // current == max → WARN-amber "N/M" + "voll"

/**
 * Derive the readout for the capacity pill from the server-authoritative snapshot (or null when unknown).
 * `null` → absent (the caller renders no pill). A null estimatedMax → "N aktiv" (current is known+authoritative, the
 * max is still pending). The `/M` is an ESTIMATE, not a hard SLA — the caller's a11y copy says so.
 */
export function capacityReadout(cap: Capacity | null | undefined): CapacityReadout {
  if (cap == null) return { kind: 'absent' }
  const max = cap.estimatedMax
  if (typeof max !== 'number' || !Number.isFinite(max) || max < 0) return { kind: 'nomax', current: cap.current }
  return cap.current >= max
    ? { kind: 'full', current: cap.current, max }
    : { kind: 'headroom', current: cap.current, max }
}

/**
 * The overload banner visibility (Q5): visible ⇔ a real server reject is active AND not dismissed. It **self-clears**
 * when capacity is known and NOT full (the machine limit is no longer reached) — computed here so the App only holds
 * the two raw flags. `capacity` null (unknown) does NOT self-clear a live reject (we can't prove headroom returned).
 */
export function overloadVisible(active: boolean, dismissed: boolean, capacity: Capacity | null | undefined): boolean {
  if (!active || dismissed) return false
  if (capacity != null && !isFull(capacity)) return false // headroom returned → self-clear
  return true
}
