// CYP-822 (CYP-807-A1) — mutation-proven teeth for the connect-progression state machine. ★-marked tests are the
// load-bearing assertions; each carries a `// MUT:` note naming the mutation it kills. Two layers: (1) the pure reducer
// folded directly (per transition + per cause + guards), (2) the machine driven by the FakeRemoteConnector end-to-end.
import { describe, it, expect, vi } from 'vitest'
import {
  remoteConnReduce,
  createRemoteConnMachine,
  initialRemoteConnState,
  failureDisposition,
  type RemoteConnState,
  type ConnectFailureCause,
  type IssuerConnectVerdict,
} from './remoteConnState'
import { FakeRemoteConnector } from './testing/fakeRemoteConnector'

// ── layer 1: the pure reducer ────────────────────────────────────────────────────────────────────────────────────

describe('remoteConnReduce — happy-path progression', () => {
  it('★ idle --dial--> dialing', () => {
    // MUT: drop the `state.phase === 'idle'` guard on dial → dial would fire from any phase.
    expect(remoteConnReduce({ phase: 'idle' }, { kind: 'dial' })).toEqual({ phase: 'dialing' })
  })

  it('★ dialing --handshakeOpen--> handshake', () => {
    // MUT: retarget handshakeOpen (e.g. to 'connected') → this reds.
    expect(remoteConnReduce({ phase: 'dialing' }, { kind: 'handshakeOpen' })).toEqual({ phase: 'handshake' })
  })

  it('★ handshake --handshakeOk--> trust-check', () => {
    // MUT: retarget handshakeOk straight to 'connected' (skipping trust-check) → this reds.
    expect(remoteConnReduce({ phase: 'handshake' }, { kind: 'handshakeOk' })).toEqual({ phase: 'trust-check' })
  })

  it('★ trust-check --trustEvaluated(proceed, ok)--> connected', () => {
    // MUT: make resolveTrustCheck return anything but 'connected' on the all-clear → this reds.
    expect(
      remoteConnReduce({ phase: 'trust-check' }, { kind: 'trustEvaluated', verdict: { outcome: 'proceed' }, tierGate: 'ok' }),
    ).toEqual({ phase: 'connected' })
  })

  it('★ connected --dropped{terminal:true}--> lost', () => {
    // MUT: retarget dropped, or widen its guard → this reds / lets illegal drops through (see guard tests).
    expect(remoteConnReduce({ phase: 'connected' }, { kind: 'dropped', terminal: true })).toEqual({ phase: 'lost' })
  })
})

describe('remoteConnReduce — failure causes (each cause pinned)', () => {
  it("★ dialing --dialRefused--> failed('connect-refused')", () => {
    // MUT: change the cause literal → this reds. Proves the cause, not just the phase.
    expect(remoteConnReduce({ phase: 'dialing' }, { kind: 'dialRefused' })).toEqual({
      phase: 'failed',
      cause: 'connect-refused',
    })
  })

  it("★ handshake --handshakeFailed--> failed('handshake-fail')", () => {
    // MUT: change the cause literal → this reds.
    expect(remoteConnReduce({ phase: 'handshake' }, { kind: 'handshakeFailed' })).toEqual({
      phase: 'failed',
      cause: 'handshake-fail',
    })
  })

  it("★ trust-check + blocked('issuer-not-trusted') --> failed('issuer-not-trusted')", () => {
    // MUT: drop the blocked branch in resolveTrustCheck → falls through to 'connected'; this reds.
    expect(
      remoteConnReduce(
        { phase: 'trust-check' },
        { kind: 'trustEvaluated', verdict: { outcome: 'blocked', cause: 'issuer-not-trusted' }, tierGate: 'ok' },
      ),
    ).toEqual({ phase: 'failed', cause: 'issuer-not-trusted' })
  })

  it("★ trust-check + blocked('remote-not-configured') --> failed('remote-not-configured') [additive issuer cause]", () => {
    // MUT: if the verdict.cause were hardcoded to 'issuer-not-trusted' instead of threaded through → this reds. Proves the
    // extensible issuer sub-taxonomy actually carries its own cause (a future 'issuer-revoked' flows the same way).
    expect(
      remoteConnReduce(
        { phase: 'trust-check' },
        { kind: 'trustEvaluated', verdict: { outcome: 'blocked', cause: 'remote-not-configured' }, tierGate: 'ok' },
      ),
    ).toEqual({ phase: 'failed', cause: 'remote-not-configured' })
  })

  it("★ trust-check + proceed + tierGate:'rejected' --> failed('security-tier')", () => {
    // MUT: drop the tier branch → falls through to 'connected'; this reds.
    expect(
      remoteConnReduce({ phase: 'trust-check' }, { kind: 'trustEvaluated', verdict: { outcome: 'proceed' }, tierGate: 'rejected' }),
    ).toEqual({ phase: 'failed', cause: 'security-tier' })
  })

  it('★ trust-check + blocked issuer AND tierGate rejected --> ISSUER wins (fixed precedence)', () => {
    // MUT: swap the issuer/tier order in resolveTrustCheck → this yields 'security-tier'; the tooth pins issuer-first.
    expect(
      remoteConnReduce(
        { phase: 'trust-check' },
        { kind: 'trustEvaluated', verdict: { outcome: 'blocked', cause: 'issuer-not-trusted' }, tierGate: 'rejected' },
      ),
    ).toEqual({ phase: 'failed', cause: 'issuer-not-trusted' })
  })
})

describe('remoteConnReduce — guards / no-op stability (illegal edges return SAME reference)', () => {
  it('★ dial is a no-op from a non-idle phase (returns same reference)', () => {
    // MUT: remove the dial guard → dial from connected jumps to dialing; this .toBe reds.
    const s: RemoteConnState = { phase: 'connected' }
    expect(remoteConnReduce(s, { kind: 'dial' })).toBe(s)
  })

  it('★ dropped is a no-op from every non-established phase — lost/reconnecting only follow an ESTABLISHED connection', () => {
    // MUT: widen the dropped guard beyond connected/reconnecting → a mid-progression drop transitions; these .toBe reds.
    for (const phase of ['idle', 'dialing', 'handshake', 'trust-check'] as const) {
      const s: RemoteConnState = { phase }
      expect(remoteConnReduce(s, { kind: 'dropped', terminal: true })).toBe(s)
      expect(remoteConnReduce(s, { kind: 'dropped', terminal: false })).toBe(s)
    }
  })

  it('★ trustEvaluated is a no-op outside trust-check', () => {
    // MUT: remove the trust-check guard → a stray verdict in 'handshake' could jump to connected/failed; this .toBe reds.
    const s: RemoteConnState = { phase: 'handshake' }
    expect(remoteConnReduce(s, { kind: 'trustEvaluated', verdict: { outcome: 'proceed' }, tierGate: 'ok' })).toBe(s)
  })

  it('handshakeOk / handshakeFailed are no-ops outside handshake', () => {
    const s1: RemoteConnState = { phase: 'dialing' }
    expect(remoteConnReduce(s1, { kind: 'handshakeOk' })).toBe(s1)
    expect(remoteConnReduce(s1, { kind: 'handshakeFailed' })).toBe(s1)
  })

  it('dialRefused / handshakeOpen are no-ops outside dialing', () => {
    const s: RemoteConnState = { phase: 'trust-check' }
    expect(remoteConnReduce(s, { kind: 'dialRefused' })).toBe(s)
    expect(remoteConnReduce(s, { kind: 'handshakeOpen' })).toBe(s)
  })
})

describe('remoteConnReduce — reset', () => {
  it('★ reset clears every terminal/active phase to idle', () => {
    // MUT: narrow reset to only some phases → a terminal that should clear would not; this reds.
    const terminals: RemoteConnState[] = [
      { phase: 'failed', cause: 'issuer-not-trusted' },
      { phase: 'lost' },
      { phase: 'connected' },
      { phase: 'trust-check' },
    ]
    for (const s of terminals) expect(remoteConnReduce(s, { kind: 'reset' })).toEqual({ phase: 'idle' })
  })

  it('★ reset from idle is a no-op (same reference)', () => {
    // MUT: always-return-new on reset → this .toBe reds (proves the no-op-stable branch).
    const s: RemoteConnState = { phase: 'idle' }
    expect(remoteConnReduce(s, { kind: 'reset' })).toBe(s)
  })
})

describe('failureDisposition — terminal/retryable/actionable taxonomy', () => {
  // MUT: change any mapping → the matching row reds. Anchored: issuer-not-trusted=terminal, remote-not-configured=actionable.
  const cases: Array<[ConnectFailureCause, ReturnType<typeof failureDisposition>]> = [
    ['issuer-not-trusted', 'terminal'],
    ['remote-not-configured', 'actionable'],
    ['security-tier', 'terminal'],
    ['connect-refused', 'retryable'],
    ['handshake-fail', 'retryable'],
  ]
  for (const [cause, disposition] of cases) {
    it(`★ ${cause} → ${disposition}`, () => {
      expect(failureDisposition(cause)).toBe(disposition)
    })
  }
})

describe('CYP-837 — the fake connector derives via the ONE issuer→verdict producer (issuerTrustToPreVerdict), not a second one', () => {
  // The CYP-822 issuerVerdictFor was removed (it PROCEEDED on REMOTE_NOT_CONFIGURED; the ratified producer blocks it,
  // actionable). REMOTE_NOT_CONFIGURED is the one value where the two diverged, so it pins the single-source: the fake
  // must reach failed('remote-not-configured'), which is only possible via issuerTrustToPreVerdict.
  it("★ evaluateTrustFromIssuer('REMOTE_NOT_CONFIGURED') → failed('remote-not-configured') — proves the single producer", () => {
    // MUT: point the fake back at a producer that proceeds on REMOTE_NOT_CONFIGURED → this reaches 'connected'; reds.
    const { conn, machine } = wired()
    conn.dial()
    conn.openHandshake()
    conn.completeHandshake()
    conn.evaluateTrustFromIssuer('REMOTE_NOT_CONFIGURED')
    expect(machine.getState()).toEqual({ phase: 'failed', cause: 'remote-not-configured' })
  })

  it("★ evaluateTrustFromIssuer('NOT_TRUSTED') → failed('issuer-not-trusted') (both producers agreed here — unchanged)", () => {
    const { conn, machine } = wired()
    conn.dial()
    conn.openHandshake()
    conn.completeHandshake()
    conn.evaluateTrustFromIssuer('NOT_TRUSTED')
    expect(machine.getState()).toEqual({ phase: 'failed', cause: 'issuer-not-trusted' })
  })
})

// ── layer 2: the machine driven by the fake connector, end-to-end ────────────────────────────────────────────────

/** Wire a fresh connector to a fresh machine (the connector's sink = machine.send), the shape A5 wires over the wire. */
function wired(): { conn: FakeRemoteConnector; machine: ReturnType<typeof createRemoteConnMachine> } {
  const conn = new FakeRemoteConnector()
  const machine = createRemoteConnMachine()
  conn.subscribe((e) => machine.send(e))
  return { conn, machine }
}

describe('FakeRemoteConnector → machine — full progressions', () => {
  it('starts at the fail-closed idle resting state', () => {
    expect(createRemoteConnMachine().getState()).toBe(initialRemoteConnState)
    expect(initialRemoteConnState).toEqual({ phase: 'idle' })
  })

  it('★ happy path: dial → handshakeOpen → handshakeOk → evaluateTrust(TRUSTED) → connected', () => {
    // MUT: any broken edge in the chain leaves the machine short of 'connected'; this reds.
    const { conn, machine } = wired()
    conn.dial()
    conn.openHandshake()
    conn.completeHandshake()
    conn.evaluateTrustFromIssuer('TRUSTED')
    expect(machine.getState()).toEqual({ phase: 'connected' })
  })

  it('★ established connection then drop → lost', () => {
    const { conn, machine } = wired()
    conn.dial()
    conn.openHandshake()
    conn.completeHandshake()
    conn.evaluateTrustFromIssuer('TRUSTED')
    conn.drop(true)
    expect(machine.getState()).toEqual({ phase: 'lost' })
  })

  it("★ refuse at dial → failed('connect-refused')", () => {
    const { conn, machine } = wired()
    conn.dial()
    conn.refuse()
    expect(machine.getState()).toEqual({ phase: 'failed', cause: 'connect-refused' })
  })

  it("★ handshake failure → failed('handshake-fail')", () => {
    const { conn, machine } = wired()
    conn.dial()
    conn.openHandshake()
    conn.failHandshake()
    expect(machine.getState()).toEqual({ phase: 'failed', cause: 'handshake-fail' })
  })

  it("★ NOT_TRUSTED issuer at trust-check → failed('issuer-not-trusted')", () => {
    const { conn, machine } = wired()
    conn.dial()
    conn.openHandshake()
    conn.completeHandshake()
    conn.evaluateTrustFromIssuer('NOT_TRUSTED')
    expect(machine.getState()).toEqual({ phase: 'failed', cause: 'issuer-not-trusted' })
  })

  it("★ wire-supplied remote-not-configured verdict → failed('remote-not-configured') [actionable arm]", () => {
    const { conn, machine } = wired()
    conn.dial()
    conn.openHandshake()
    conn.completeHandshake()
    conn.evaluateTrust({ outcome: 'blocked', cause: 'remote-not-configured' })
    expect(machine.getState()).toEqual({ phase: 'failed', cause: 'remote-not-configured' })
    expect(failureDisposition('remote-not-configured')).toBe('actionable')
  })

  it("★ tier rejected at trust-check → failed('security-tier')", () => {
    const { conn, machine } = wired()
    conn.dial()
    conn.openHandshake()
    conn.completeHandshake()
    conn.evaluateTrust({ outcome: 'proceed' }, 'rejected')
    expect(machine.getState()).toEqual({ phase: 'failed', cause: 'security-tier' })
  })

  it('reset after a failure returns to idle and a re-dial progresses again', () => {
    const { conn, machine } = wired()
    conn.dial()
    conn.refuse()
    conn.reset()
    expect(machine.getState()).toEqual({ phase: 'idle' })
    conn.dial()
    expect(machine.getState()).toEqual({ phase: 'dialing' })
  })
})

describe('createRemoteConnMachine — subscription semantics', () => {
  it('★ notifies subscribers only on an ACTUAL state change (no-op events do not fire)', () => {
    // MUT: drop the `next === state` short-circuit in send() → the no-op drop would notify; this reds.
    const machine = createRemoteConnMachine()
    const seen = vi.fn()
    machine.subscribe(seen)
    machine.send({ kind: 'dropped', terminal: true }) // no-op from idle
    expect(seen).not.toHaveBeenCalled()
    machine.send({ kind: 'dial' }) // real change
    expect(seen).toHaveBeenCalledTimes(1)
    expect(seen).toHaveBeenLastCalledWith({ phase: 'dialing' })
  })

  it('unsubscribe stops further notifications', () => {
    const machine = createRemoteConnMachine()
    const seen = vi.fn()
    const off = machine.subscribe(seen)
    off()
    machine.send({ kind: 'dial' })
    expect(seen).not.toHaveBeenCalled()
  })
})

// ── CYP-826 A2 extension: terminal vs reconnectable drop + reconnect + outcome-union fail-closed ──────────────────

describe('CYP-826 A2 — dropped{terminal}: terminal→lost (region) vs reconnectable→reconnecting (transient)', () => {
  it('★ connected --dropped{terminal:false}--> reconnecting (transient, NOT lost / NOT a failure arm)', () => {
    // MUT: route a non-terminal drop to 'lost' (or 'failed') → this reds. A reconnectable drop must not enter the region.
    expect(remoteConnReduce({ phase: 'connected' }, { kind: 'dropped', terminal: false })).toEqual({ phase: 'reconnecting' })
  })

  it('★ connected --dropped{terminal:true}--> lost (terminal → routes to the failure region)', () => {
    // MUT: route a terminal drop to 'reconnecting' → this reds. Terminal-lost must reach the region.
    expect(remoteConnReduce({ phase: 'connected' }, { kind: 'dropped', terminal: true })).toEqual({ phase: 'lost' })
  })

  it('★ reconnecting --dropped{terminal:true}--> lost (a reconnect that gives up is terminal)', () => {
    expect(remoteConnReduce({ phase: 'reconnecting' }, { kind: 'dropped', terminal: true })).toEqual({ phase: 'lost' })
  })

  it('★ reconnecting --dropped{terminal:false}--> reconnecting (no-op, SAME reference)', () => {
    // MUT: return a fresh object for a still-reconnecting drop → this .toBe reds (identity churn).
    const s: RemoteConnState = { phase: 'reconnecting' }
    expect(remoteConnReduce(s, { kind: 'dropped', terminal: false })).toBe(s)
  })

  it('★ reconnecting --dial--> dialing (retry re-enters the progression)', () => {
    // MUT: keep the dial guard idle-only → reconnecting could never retry; this reds.
    expect(remoteConnReduce({ phase: 'reconnecting' }, { kind: 'dial' })).toEqual({ phase: 'dialing' })
  })

  it('★ reconnecting --reset--> idle', () => {
    expect(remoteConnReduce({ phase: 'reconnecting' }, { kind: 'reset' })).toEqual({ phase: 'idle' })
  })

  it('★ resolveTrustCheck outcome-union is fail-CLOSED: an unknown outcome THROWS, never silently connects', () => {
    // MUT: replace the outcome `switch`+assertNever with `if (blocked) … else return connected` → an unknown outcome
    // fail-OPENs to connected instead of throwing; this reds. (assertNever is compile-time; the cast simulates a future
    // unhandled variant reaching runtime — the fail-closed guarantee Assist2 asked for.)
    expect(() =>
      remoteConnReduce(
        { phase: 'trust-check' },
        { kind: 'trustEvaluated', verdict: { outcome: 'mystery' } as unknown as IssuerConnectVerdict, tierGate: 'ok' },
      ),
    ).toThrow()
  })

  it('★ fake connector: connected → drop(false) → reconnecting → dial → dialing (reconnect retry, end-to-end)', () => {
    const { conn, machine } = wired()
    conn.dial()
    conn.openHandshake()
    conn.completeHandshake()
    conn.evaluateTrustFromIssuer('TRUSTED')
    conn.drop(false)
    expect(machine.getState()).toEqual({ phase: 'reconnecting' })
    conn.dial()
    expect(machine.getState()).toEqual({ phase: 'dialing' })
  })
})
