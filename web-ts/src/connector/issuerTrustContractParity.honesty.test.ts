// ══════════════════════════════════════════════════════════════════════════════════════════════════════════════════
// CYP-805 P3 (post-CYP-804 re-point) — cross-source parity for the LOCAL runtime companion `HUB_ISSUER_TRUST_VALUES`
// against the GENERATED `HubIssuerTrust` union. After the re-point, issuerTrustModel imports the type from the generated
// `contract.ts`; the generator emits the TYPE ONLY (no runtime values array), so `HUB_ISSUER_TRUST_VALUES` stays a LOCAL
// hand-written companion — the one thing that can silently DRIFT from the authority.
//
// ★ THE DIRECTION Dev5's `satisfies` does NOT catch: `HUB_ISSUER_TRUST_VALUES … satisfies readonly HubIssuerTrust[]`
// guarantees array ⊆ union (a STRAY local value fails to compile). It does NOT guarantee union ⊆ array — if the generated
// union GROWS a member (a 4th issuer state added to the openapi/contract), the 3-element companion still satisfies
// (3 valid ⊆ 4) yet is now STALE/incomplete. This tooth is the missing union ⊆ array direction, RED-on-drift, in two
// mutually-reinforcing lenses:
//   (runtime, executable) the local array set-equals the openapi authority `HubIssuerTrust.enum` (both directions);
//   (compile-time) `Exclude<HubIssuerTrust, array[number]>` must be `never` — bound to the IMPORTED generated type, so
//     tsc -b (which type-checks src/**/*.test.ts) reddens the moment the generated union outgrows the array.
// Team-1's mechanized ②-gate covers openapi == :core (server side); THIS is the client-side 2nd lens (companion == union).
// ══════════════════════════════════════════════════════════════════════════════════════════════════════════════════
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { HUB_ISSUER_TRUST_VALUES } from './issuerTrustModel'
import type { HubIssuerTrust } from './issuerTrustModel'

/** The generated union's SOURCE OF TRUTH: `components.schemas.HubIssuerTrust.enum` in the committed openapi.json, which
 *  `contract:gen` emits verbatim into the generated `HubIssuerTrust` type. Reading it (not the gitignored generated .ts)
 *  keeps the authority a stable committed artifact. */
function generatedIssuerUnion(): string[] {
  const raw = readFileSync(resolve(process.cwd(), 'contract/openapi.json'), 'utf8')
  const enumv = JSON.parse(raw)?.components?.schemas?.HubIssuerTrust?.enum
  return Array.isArray(enumv) ? (enumv as string[]) : []
}

const sorted = (xs: readonly string[]) => [...xs].sort()
const local = HUB_ISSUER_TRUST_VALUES as readonly string[]

describe('CYP-805 P3 — HUB_ISSUER_TRUST_VALUES mirrors the generated HubIssuerTrust union (RED-on-drift)', () => {
  const authority = generatedIssuerUnion()

  it('non-vacuity: the generated HubIssuerTrust enum is found in openapi.json (a missing authority must fail, not pass)', () => {
    expect(authority.length).toBeGreaterThan(0)
  })

  it('★ union ⊆ array: the local companion COVERS the full generated union — the direction `satisfies` does NOT catch', () => {
    // RED if the generated union gains a member the local array omits (stale companion). This is the load-bearing check.
    const uncovered = authority.filter((m) => !local.includes(m))
    expect(uncovered, `generated union member(s) missing from HUB_ISSUER_TRUST_VALUES: ${uncovered.join(', ')}`).toEqual([])
  })

  it('array ⊆ union: no stray local value absent from the generated union (runtime 2nd lens to Dev5’s compile-time satisfies)', () => {
    const stray = local.filter((v) => !authority.includes(v))
    expect(stray, `local value(s) not in the generated union: ${stray.join(', ')}`).toEqual([])
  })

  it('exact set-equality both directions (the companion is a faithful mirror of the generated union)', () => {
    expect(sorted(local)).toEqual(sorted(authority))
  })
})

// ── compile-time union ⊆ array, bound to the IMPORTED generated type (build-enforced; complements the runtime lens) ──
// If the generated `HubIssuerTrust` union grows a member the local array omits, `_Uncovered` is a non-`never` literal
// union → `[non-never] extends [never]` is `false` → `const _x: false = true` fails `tsc -b`. (`[T]` tuple-wraps so a
// distributive conditional can't collapse the check.)
type _UncoveredUnionMembers = Exclude<HubIssuerTrust, (typeof HUB_ISSUER_TRUST_VALUES)[number]>
const _P3_UNION_FULLY_COVERED: [_UncoveredUnionMembers] extends [never] ? true : false = true
void _P3_UNION_FULLY_COVERED
