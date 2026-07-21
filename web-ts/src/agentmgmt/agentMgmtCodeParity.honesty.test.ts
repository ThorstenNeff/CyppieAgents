import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'

// CYP-776 — cross-source parity for the agent-management error codes. `:core` AgentMgmtGuard returns FREE-FORM code
// strings (`invalid_agent` / `agent_exists` / `po_already_exists` / `agent_not_found` / `last_po`); the client
// (agentMgmtModel.ts) `switch (restErrorCode(err))`es on a SUBSET of them to render specific messages. These codes
// are bare wire strings — NOT in the generated contract — so TypeScript gives zero protection. Rename a code on the
// server and the client's `switch` silently falls to `default` → the generic "failed" message, collapsing the
// deliberate `po_already_exists` (a 2nd PO) vs `last_po` (the only PO stepping down) distinction. Both sides' own
// suites (AgentMgmtGuardTest.kt, agentMgmtModel.test.ts) stay green because each pins only its own half.
//
// ★ DIRECTIONAL, NOT SYMMETRIC (a2-po gate requirement): the invariant is client ⊆ authority — every code the client
// switches on must be a REAL :core code (a client `case` for a code the server never emits looks like handling and is
// dead). It is NOT authority ⊆ client: the client deliberately handles only a subset (invalid_agent / agent_not_found
// fall to generic by design), so a symmetric check would be FALSE-RED on exactly those two.
//
// ★ NON-VACUITY on BOTH sides: an anchor-miss that returns [] would make "client ⊆ authority" vacuously true (∅ ⊆ X),
// so BOTH extractions are asserted non-empty first — the authority AND the client. A tooth whose subset check can pass
// on an empty set is a missed-anchor tooth.
//
// Mutations (one per line, proven at the object on this branch): a bogus client `case 'nonexistent_code'` → the
// directional line REDs; a `:core` rename of a code the client uses → the directional line REDs (client case no longer
// real). Comment-safe on the client side (only real `case` tokens inside a restErrorCode switch are read).

function readUp(rel: string): string {
  let dir = process.cwd()
  for (;;) {
    const p = resolve(dir, rel)
    if (existsSync(p)) return readFileSync(p, 'utf8')
    const up = dirname(dir)
    if (up === dir) throw new Error(`could not locate ${rel} from ${process.cwd()} upwards`)
    dir = up
  }
}

/** Authority: every `return "<code>"` string in :core AgentMgmtGuard.kt. These ARE the emittable codes. */
function authorityCodes(): string[] {
  const src = readUp('core/src/commonMain/kotlin/com/tneff/cyppieagents/model/AgentMgmtGuard.kt')
  return [...new Set([...src.matchAll(/return\s+"([a-z_]+)"/g)].map((m) => m[1]))]
}

/** Client: the `case '<code>'` literals that live INSIDE a `switch (restErrorCode(...))` block — scoped by brace
 *  matching so the unrelated `switch (role)` (PO/WORKER/PRODUCT_LEAD) is never mistaken for an error code. */
function clientCodes(): string[] {
  const src = readUp('web-ts/src/agentmgmt/agentMgmtModel.ts')
  const out: string[] = []
  for (const m of src.matchAll(/switch\s*\(\s*restErrorCode\s*\(/g)) {
    const open = src.indexOf('{', (m.index ?? 0) + m[0].length)
    let depth = 0
    let j = open
    for (; j < src.length; j++) {
      if (src[j] === '{') depth++
      else if (src[j] === '}' && --depth === 0) break
    }
    for (const c of src.slice(open, j).matchAll(/case\s+'([^']+)'/g)) out.push(c[1])
  }
  return [...new Set(out)]
}

describe('CYP-776 — agent-mgmt error-code parity (client ⊆ :core authority)', () => {
  const authority = authorityCodes()
  const client = clientCodes()
  const authoritySet = new Set(authority)

  it('non-vacuity: the :core authority extraction finds codes (anchor-miss → [] would false-pass the subset check)', () => {
    expect(authority.length).toBeGreaterThan(0)
  })

  it('non-vacuity: the client restErrorCode-switch extraction finds codes (∅ ⊆ X is vacuously true)', () => {
    expect(client.length).toBeGreaterThan(0)
  })

  it('★ every client-handled code is a REAL :core code (client ⊆ authority — directional, not symmetric)', () => {
    const orphans = client.filter((c) => !authoritySet.has(c))
    expect(orphans, `client switches on code(s) :core never emits: ${orphans.join(', ')}`).toEqual([])
  })

  it('sanity: the client handles a SUBSET, and the codes it DOES handle are the specific-message ones', () => {
    // documents WHY the check is directional: :core emits more than the client specific-cases (the rest → generic).
    // NOT an assertion of a fixed count (that would break when either side legitimately grows) — just that the
    // authority is at least as large as the handled set, i.e. a subset relationship is even possible.
    expect(authority.length).toBeGreaterThanOrEqual(client.length)
  })

  it('coverage-by-construction: codes are dispatched via restErrorCode, never by matching the error MESSAGE', () => {
    const src = readUp('web-ts/src/agentmgmt/agentMgmtModel.ts')
    expect(src).toContain('restErrorCode')
    // a message-string dispatch is the anti-pattern rest.ts itself warns against (the message is human copy, not a
    // stable machine key). None of `.message ===` / `.message.includes(` / `.message.match(` for dispatch.
    expect(src).not.toMatch(/\.message\s*(===|!==|\.includes\(|\.match\()/)
  })
})
