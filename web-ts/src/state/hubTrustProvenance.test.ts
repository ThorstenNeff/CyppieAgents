import { describe, it, expect } from 'vitest'
import { emptyTrustProvenance, recordObservation, displayedTrust } from './hubTrustProvenance'

describe('CYP-853 (Multi-Hub M3) — hubTrustProvenance: switch-first trust degradation (MC-2, immediate lapse)', () => {
  it('the ACTIVE hub shows its live observed trust (we are observing it now)', () => {
    const p = recordObservation(emptyTrustProvenance, 'local', 'TRUSTED', 1000)
    expect(displayedTrust(p, 'local', 'local')).toBe('TRUSTED')
  })

  it('the ACTIVE hub is UNKNOWN until first observed (fail-closed, never optimistic)', () => {
    expect(displayedTrust(emptyTrustProvenance, 'local', 'local')).toBe('UNKNOWN')
  })

  it('★ an INACTIVE hub that WAS trusted → STALE, NEVER cached-`trusted` (the core honesty bug this forbids)', () => {
    // MUT: displayedTrust returning obs.trust for the inactive branch (keep showing cached trusted) → reds.
    const p = recordObservation(emptyTrustProvenance, 'alpha', 'TRUSTED', 1000)
    expect(displayedTrust(p, 'alpha', 'local')).toBe('STALE')
  })

  it('★ an INACTIVE hub NEVER observed → UNKNOWN, never a fabricated STALE (stale ⇒ there WAS an observation)', () => {
    // MUT: never-observed → STALE (inventing a prior observation) → reds.
    expect(displayedTrust(emptyTrustProvenance, 'alpha', 'local')).toBe('UNKNOWN')
  })

  it('★ an INACTIVE hub that was REJECTED → REJECTED (a fail-closed negative is preserved, not softened)', () => {
    const p = recordObservation(emptyTrustProvenance, 'alpha', 'REJECTED', 1000)
    expect(displayedTrust(p, 'alpha', 'local')).toBe('REJECTED')
  })

  it('an INACTIVE hub last seen pending/unknown → UNKNOWN (nothing affirmative to show without observation)', () => {
    const pPending = recordObservation(emptyTrustProvenance, 'alpha', 'PENDING', 1000)
    expect(displayedTrust(pPending, 'alpha', 'local')).toBe('UNKNOWN')
  })

  it('★ MC-1 no cross-hub leak: hub A’s observed trust does NOT bleed into hub B’s display', () => {
    // A observed trusted, B never observed; active = A.
    const p = recordObservation(emptyTrustProvenance, 'A', 'TRUSTED', 1000)
    expect(displayedTrust(p, 'A', 'A')).toBe('TRUSTED') // active A: live
    expect(displayedTrust(p, 'B', 'A')).toBe('UNKNOWN') // B never observed — A's trust must NOT leak to B
    // switch active to C: A lapses to STALE (its OWN provenance), B still UNKNOWN (unchanged) — per-hub, isolated.
    expect(displayedTrust(p, 'A', 'C')).toBe('STALE')
    expect(displayedTrust(p, 'B', 'C')).toBe('UNKNOWN')
  })

  it('★ provenance carries the observation TIMESTAMP (the seam for a future window), and a later observation replaces it', () => {
    let p = recordObservation(emptyTrustProvenance, 'alpha', 'TRUSTED', 1000)
    expect(p['alpha']).toEqual({ trust: 'TRUSTED', observedAt: 1000 })
    p = recordObservation(p, 'alpha', 'REJECTED', 2000) // a later observation supersedes
    expect(p['alpha']).toEqual({ trust: 'REJECTED', observedAt: 2000 })
    expect(displayedTrust(p, 'alpha', 'alpha')).toBe('REJECTED')
  })
})
