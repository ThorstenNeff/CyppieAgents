// CYP-814 Batch-1 G4 + G1 — two reconnect/error invariants that lived as untested inline logic in App (now extracted to
// named exports so the invariant is a tested unit; behaviour unchanged):
//   G4 byOrder — the timeline comparator (App.tsx, 0 tests): ascending by the STORED message's authoritative seq, else ts.
//               a `b-a` (reverse) mutation would render the whole timeline newest-first and survived (no test).
//   G1 commCloseToConnection — the WS-close→connection map: 1008 = terminal `revoked`, any other = reconnectable
//               `offline`. A `1008 ? 'revoked' : 'revoked'` mutation would paint a transient drop with the terminal
//               revoked tone/composer-lock and survived (the arm had no test).
import { describe, it, expect } from 'vitest'
import { byOrder, commCloseToConnection, nextCommConnectionOnClose, nextCommConnectionOnSkew } from './App'
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

describe('CYP-834 — nextCommConnectionOnClose: a TERMINAL state (skew/revoked) is sticky, a close code cannot downgrade it', () => {
  it('★ current=skew stays skew for ANY close code (a decoded protocol-skew takes precedence over the close)', () => {
    // MUT: drop the terminal-latch (always return commCloseToConnection(code)) → a later close overwrites skew; reds.
    for (const code of [1008, 1006, 1000, undefined]) {
      expect(nextCommConnectionOnClose('skew', code), `code ${code}`).toBe('skew')
    }
  })

  it('★ current=revoked stays revoked (a terminal revoke is not downgraded by a later transient close)', () => {
    expect(nextCommConnectionOnClose('revoked', 1006)).toBe('revoked')
    expect(nextCommConnectionOnClose('revoked', undefined)).toBe('revoked')
  })

  it('★ a non-terminal current defers to commCloseToConnection(code) (1008→revoked, else→offline)', () => {
    // MUT: latch ALL states (return current always) → a real close would never update a live/offline banner; reds.
    expect(nextCommConnectionOnClose('live', 1008)).toBe('revoked')
    expect(nextCommConnectionOnClose('live', 1006)).toBe('offline')
    expect(nextCommConnectionOnClose('connecting', 1008)).toBe('revoked')
    expect(nextCommConnectionOnClose('offline', 1006)).toBe('offline')
  })
})

describe('CYP-845 (F-A5-1) — nextCommConnectionOnSkew: a skew respects the SAME first-terminal-cause latch (no security-mask)', () => {
  it('★ current=revoked stays revoked (the honesty fix — a buffered skew delivered AFTER a 1008-revoke must NOT downgrade it)', () => {
    // THE bug: without the latch, onCommSkew unconditionally set 'skew' → 'revoked'→'skew' masks the security revoke.
    // MUT: drop the latch (always return 'skew') → this reds.
    expect(nextCommConnectionOnSkew('revoked')).toBe('revoked')
  })

  it('★ current=skew stays skew (idempotent — a second skew is not a state change)', () => {
    expect(nextCommConnectionOnSkew('skew')).toBe('skew')
  })

  it('★ a non-terminal current ESCALATES to skew (skew is terminal — it must not be swallowed by live/offline/connecting)', () => {
    // MUT: latch ALL states (return current always) → a real skew would never surface over a live/offline banner; reds.
    for (const current of ['live', 'connecting', 'offline'] as const) {
      expect(nextCommConnectionOnSkew(current), `from ${current}`).toBe('skew')
    }
  })
})
