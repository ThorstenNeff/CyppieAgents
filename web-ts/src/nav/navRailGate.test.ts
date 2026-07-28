// CYP-888 (NR-1) — the responsive-gate tooth. This is the load-bearing "gate bites" check: landscape + short-edge ≥600dp
// shows the rail; portrait or any smaller short edge does not. Mutation probes (invert the landscape test / drop the
// threshold) both go red here.
import { describe, it, expect } from 'vitest'
import { shouldShowNavRail, NAV_RAIL_MIN_SHORT_EDGE_DP } from './navRailGate'

describe('CYP-888 — shouldShowNavRail (responsive gate)', () => {
  it('★ landscape + short-edge ≥ 600dp → rail', () => {
    // MUT: invert the landscape condition → this reds. MUT: raise threshold above 768 → this reds.
    expect(shouldShowNavRail({ width: 1024, height: 768 })).toBe(true)
    expect(shouldShowNavRail({ width: 1280, height: 800 })).toBe(true)
  })

  it('★ portrait (width ≤ height) → NO rail, even with a large short edge', () => {
    // MUT: drop the landscape condition (threshold only) → a 800×1280 portrait tablet would wrongly show the rail → red.
    expect(shouldShowNavRail({ width: 800, height: 1280 })).toBe(false)
    expect(shouldShowNavRail({ width: 768, height: 1024 })).toBe(false)
  })

  it('★ landscape but short-edge < 600dp → NO rail (phone landscape)', () => {
    // MUT: drop the threshold (landscape only) → a 900×500 phone-landscape would wrongly show the rail → red.
    expect(shouldShowNavRail({ width: 900, height: 500 })).toBe(false)
    expect(shouldShowNavRail({ width: 640, height: 360 })).toBe(false)
  })

  it('★ boundary: short edge exactly 600dp in landscape → rail (inclusive ≥)', () => {
    // MUT: make the comparison strict (> instead of ≥) → this reds.
    expect(shouldShowNavRail({ width: 900, height: NAV_RAIL_MIN_SHORT_EDGE_DP })).toBe(true)
    expect(shouldShowNavRail({ width: 900, height: NAV_RAIL_MIN_SHORT_EDGE_DP - 1 })).toBe(false)
  })

  it('★ square viewport is NOT landscape → NO rail', () => {
    // width === height must not count as landscape (short edge 700 ≥ 600, so only the strict-> guard keeps it off).
    expect(shouldShowNavRail({ width: 700, height: 700 })).toBe(false)
  })
})
