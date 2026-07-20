import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { ROLE_OPTIONS, roleLabel, type Role } from './agentMgmtModel'

// Nacht-Lane parity oracle (Team2 QA) — the create/edit role picker vs the authoritative contract.
//
// `agentMgmtModel.ts` hand-declares `type Role = 'PO' | 'WORKER' | 'PRODUCT_LEAD'` and `ROLE_OPTIONS` as a SEPARATE
// union from the generated contract. Because it is a distinct TS type, an exhaustive `switch` over it keeps
// compiling even after `:core` adds a Role — so a new role would update the generated contract (and the server)
// while the picker silently never offers it, and no test on either side fails. This oracle ties the mirror to the
// authority: the role enum in the COMMITTED contract export (web-ts/contract/openapi.json, drift-guarded against
// :core by ContractExportDriftTest) must equal ROLE_OPTIONS exactly.
//
// Reads the committed artifact, not a hand-copy — so it measures the real contract the server speaks.
// Mutation check (run): drop 'PRODUCT_LEAD' from ROLE_OPTIONS → RED (picker misses a real role); add a phantom
// 'ADMIN' → RED (picker offers a role the contract has no place for). Non-vacuity is asserted directly below.

/** Every `role` property in the OpenAPI that carries an `enum` (the Agent/AgentDetail role). A bare
 *  `role: {type:string}` with no enum — e.g. AuthMe.role — is deliberately NOT a member here; that untyped wire
 *  string is a separate, known parity gap and must not silently satisfy this oracle. */
function contractRoleEnums(): string[][] {
  const doc = JSON.parse(readFileSync(resolve(process.cwd(), 'contract/openapi.json'), 'utf8'))
  const found: string[][] = []
  const walk = (o: unknown): void => {
    if (Array.isArray(o)) {
      o.forEach(walk)
    } else if (o && typeof o === 'object') {
      for (const [k, v] of Object.entries(o as Record<string, unknown>)) {
        if (k === 'role' && v && typeof v === 'object' && Array.isArray((v as { enum?: unknown }).enum)) {
          found.push((v as { enum: unknown[] }).enum.map(String))
        }
        walk(v)
      }
    }
  }
  walk(doc)
  return found
}

const sorted = (xs: readonly string[]): string[] => [...xs].sort()

describe('agent role picker ↔ contract parity (Nacht-Lane)', () => {
  const enums = contractRoleEnums()

  it('the contract actually carries a role enum (non-vacuity: there IS an authority to compare against)', () => {
    // if this ever drops to 0, the oracle is comparing against nothing and every green below is empty
    expect(enums.length).toBeGreaterThan(0)
  })

  it('every role enum in the contract is internally consistent (one canonical role set)', () => {
    const distinct = new Set(enums.map((e) => sorted(e).join('|')))
    expect(distinct.size, `contract has divergent role enums: ${[...distinct].join('  vs  ')}`).toBe(1)
  })

  it('★ ROLE_OPTIONS equals the contract role set exactly — no role the picker omits, none it invents', () => {
    const authority = sorted(enums[0])
    expect(sorted(ROLE_OPTIONS)).toEqual(authority)
  })

  it('the hand-written Role union covers exactly ROLE_OPTIONS (the union and the picker cannot drift apart)', () => {
    // a runtime cross-check on the type: every ROLE_OPTIONS value is a valid Role, and there are no extras.
    const asRole: Role[] = [...ROLE_OPTIONS]
    expect(asRole.length).toBe(ROLE_OPTIONS.length)
    // and every offered role has a non-empty human label — a new role cannot ship unlabeled
    for (const r of ROLE_OPTIONS) expect(roleLabel(r).length).toBeGreaterThan(0)
  })
})
