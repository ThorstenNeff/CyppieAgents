// CYP-751 — PARITY ORACLE: the client's AUTH_ROLE constants must match the authoritative :core declaration of
// AuthMe.role. The contract carries `AuthMe.role` as a bare `string` by deliberate :core design (a thin cross-module
// contract), so NOTHING at compile time pins the client literal to what the server actually emits — a bare-string
// drift would silently fail-close operators to MEMBER with a green suite. This reads the ONE authoritative source,
// the `[role]` KDoc union in :core AuthMe.kt (`"OPERATOR" | "MEMBER"`), and fails RED if the client drifts from it.
//
// ★ NON-VACUITY (PL): the anchor targets EXACTLY the `[role]` declaration, not any quoted string in the file. A
// wrong/missing anchor yields an EMPTY `declaredRoles()`, which reds the non-empty + set-equality assertions — a
// mis-anchored oracle cannot pass green. Prove-red: change the union value in AuthMe.kt (e.g. OPERATOR→ADMIN) → the
// set-equality reds; change AUTH_ROLE.OPERATOR on the client → it reds too (both directions pinned).
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { AUTH_ROLE } from './authRole'

// The authoritative source: :core AuthMe.kt, one directory up from web-ts (monorepo). If it moves, this reds — which
// is correct: the oracle must be re-anchored to wherever the declaration lives, never silently stop checking.
const AUTHME_KT = resolve(process.cwd(), '../core/src/commonMain/kotlin/com/tneff/cyppieagents/model/AuthMe.kt')

/**
 * The role values declared in the `[role]` KDoc union of :core AuthMe.kt:
 *   `- [role] — the platform authZ role (`"OPERATOR"` | `"MEMBER"`) once established, else null …`
 * Anchored on `[role] … authZ role (…)` so it captures THAT union's parenthesised span only — not the later
 * `(e.g. …)` aside, and not any other quoted string elsewhere in the file. Returns [] if the anchor misses.
 */
function declaredRoles(): string[] {
  const src = readFileSync(AUTHME_KT, 'utf8')
  const m = src.match(/\[role\][^\n]*?authZ role\s*\(([^)]*)\)/)
  if (m === null) return []
  return [...m[1].matchAll(/"([A-Z_]+)"/g)].map((x) => x[1])
}

describe('CYP-751 — AUTH_ROLE parity with the authoritative :core AuthMe.kt declaration', () => {
  it('★ the client constants EXACTLY match the [role] union in :core AuthMe.kt (RED on drift, both directions)', () => {
    const declared = declaredRoles()
    // Non-vacuity: the anchor actually found the declaration. A wrong anchor → [] → this reds FIRST, never a false green.
    expect(declared.length, 'anchor missed the [role] union in AuthMe.kt — re-anchor, do not weaken').toBeGreaterThan(0)
    expect(new Set(declared)).toEqual(new Set(Object.values(AUTH_ROLE)))
  })

  it('★ the anchor hit the [role] declaration specifically — OPERATOR is in the parsed union', () => {
    // Guards a regex that matched but captured the wrong span: the value the whole fail-closed operator gate turns on
    // MUST be the one we parsed from the union, not merely present somewhere in the client constants.
    expect(declaredRoles()).toContain('OPERATOR')
  })

  it('every client role literal is a non-empty upper-case token (no accidental blank/lower-case constant)', () => {
    for (const v of Object.values(AUTH_ROLE)) expect(v).toMatch(/^[A-Z_]+$/)
  })
})
