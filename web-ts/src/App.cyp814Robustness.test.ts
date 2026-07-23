// CYP-814 Batch-1 G4 + G1 — two reconnect/error invariants that lived as untested inline logic in App (now extracted to
// named exports so the invariant is a tested unit; behaviour unchanged):
//   G4 byOrder — the timeline comparator (App.tsx, 0 tests): ascending by the STORED message's authoritative seq, else ts.
//               a `b-a` (reverse) mutation would render the whole timeline newest-first and survived (no test).
//   G1 commCloseToConnection — the WS-close→connection map: 1008 = terminal `revoked`, any other = reconnectable
//               `offline`. A `1008 ? 'revoked' : 'revoked'` mutation would paint a transient drop with the terminal
//               revoked tone/composer-lock and survived (the arm had no test).
import { describe, it, expect } from 'vitest'
import { byOrder, commCloseToConnection } from './App'
import type { DeliveredMessage } from './types/generated/contract'

const dm = (id: string, over: { seq?: number; ts?: number }): DeliveredMessage =>
  ({ message: { id, channelId: 'c', from: 'a', body: '', ts: over.ts ?? 0, ...(over.seq !== undefined ? { seq: over.seq } : {}) } }) as DeliveredMessage

describe('CYP-814 G4 — byOrder sorts the timeline ASCENDING (oldest→newest), by authoritative seq else ts', () => {
  it('★ two authoritative-seq messages sort ascending by seq — a `b-a` reverse mutation REDs', () => {
    const a = dm('a', { seq: 1 }), b = dm('b', { seq: 2 })
    expect(byOrder(a, b)).toBeLessThan(0)
    expect(byOrder(b, a)).toBeGreaterThan(0)
    expect([b, a].sort(byOrder).map((d) => d.message.id)).toEqual(['a', 'b'])
  })

  it('★ without an authoritative seq, sorts ascending by ts (the fallback key)', () => {
    const a = dm('a', { ts: 10 }), b = dm('b', { ts: 20 })
    expect(byOrder(a, b)).toBeLessThan(0)
    expect([b, a].sort(byOrder).map((d) => d.message.id)).toEqual(['a', 'b'])
  })
})

describe('CYP-814 G1 — commCloseToConnection: 1008 is a TERMINAL revoke; every other drop is a reconnectable offline', () => {
  it('★ 1008 (auth revoked) → revoked (terminal — no reconnect, composer locks)', () => {
    expect(commCloseToConnection(1008)).toBe('revoked')
  })

  it('★ any NON-1008 (transient) drop → offline (reconnectable — never the terminal revoked tone/lock)', () => {
    // a `1008 ? 'revoked' : 'revoked'` mutation makes one of these 'revoked' → RED.
    for (const code of [1000, 1001, 1005, 1006, 1011, 4000]) {
      expect(commCloseToConnection(code), `close code ${code}`).toBe('offline')
    }
  })

  it('★ an absent close code (undefined) → offline, never revoked (a missing code is a transient drop, not a 1008 revoke)', () => {
    expect(commCloseToConnection(undefined)).toBe('offline')
  })
})
