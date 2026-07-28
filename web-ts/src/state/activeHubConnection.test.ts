import { describe, it, expect } from 'vitest'
import { createActiveHubConnection } from './activeHubConnection'
import { createRemoteConnMachine, failureDisposition } from './remoteConnState'
import { FakeRemoteConnector } from './testing/fakeRemoteConnector'

/** A recording connector: tracks which hubs were opened and which were torn down (close()). */
const recordingConnector = (opens: string[], closes: string[]) => (hubId: string) => {
  opens.push(hubId)
  return { machine: createRemoteConnMachine(), close: () => closes.push(hubId) }
}

describe('CYP-853 (Multi-Hub M3) — activeHubConnection: ONE-ACTIVE switch-first lifecycle', () => {
  it('switchTo opens a live connection for the target hub', () => {
    const opens: string[] = []
    const ahc = createActiveHubConnection(recordingConnector(opens, []))
    ahc.switchTo('local')
    expect(opens).toEqual(['local'])
    expect(ahc.active()?.hubId).toBe('local')
  })

  it('★ switching TEARS DOWN the old before opening the new — exactly ONE live at a time (no background conn survives)', () => {
    // MUT: open the new without closing the old → the inactive hub keeps a background connection (breaks switch-first,
    // and the immediate-STALE precondition) → closes stays [] → reds.
    const opens: string[] = []
    const closes: string[] = []
    const ahc = createActiveHubConnection(recordingConnector(opens, closes))
    ahc.switchTo('local')
    ahc.switchTo('remote')
    expect(closes).toEqual(['local']) // the OLD hub's connection was torn down
    expect(opens).toEqual(['local', 'remote'])
    expect(opens.length - closes.length).toBe(1) // exactly one live connection
    expect(ahc.active()?.hubId).toBe('remote')
  })

  it('★ switch-to-active is a no-op: the live connection is NOT torn down and re-dialed', () => {
    // MUT: drop the same-hub guard → switching to the current hub closes + re-opens it (a needless reconnect) → reds.
    const opens: string[] = []
    const closes: string[] = []
    const ahc = createActiveHubConnection(recordingConnector(opens, closes))
    ahc.switchTo('local')
    ahc.switchTo('local')
    expect(opens).toEqual(['local'])
    expect(closes).toEqual([])
  })

  it('close() tears down the active connection and clears it', () => {
    const closes: string[] = []
    const ahc = createActiveHubConnection(recordingConnector([], closes))
    ahc.switchTo('local')
    ahc.close()
    expect(closes).toEqual(['local'])
    expect(ahc.active()).toBeNull()
  })

  it('★ MC-6: the active connection surfaces an ACTIONABLE failure (remote-not-configured) through a real machine', () => {
    // The conn-model drives a REAL createRemoteConnMachine; a remote-not-configured verdict lands failed(actionable),
    // not terminal/retryable. MUT: a connector that swallowed the verdict / a wrong disposition mapping → reds.
    const fake = new FakeRemoteConnector()
    const ahc = createActiveHubConnection(() => {
      const machine = createRemoteConnMachine()
      const unsub = fake.subscribe(machine.send)
      return { machine, close: unsub }
    })
    ahc.switchTo('remote')
    fake.dial()
    fake.openHandshake()
    fake.completeHandshake()
    fake.evaluateTrustFromIssuer('REMOTE_NOT_CONFIGURED')
    const st = ahc.active()!.machine.getState()
    expect(st.phase).toBe('failed')
    if (st.phase === 'failed') {
      expect(st.cause).toBe('remote-not-configured')
      expect(failureDisposition(st.cause)).toBe('actionable') // not terminal, not retryable
    }
  })
})
