// CYP-807-A5 — RemoteHubConnector progression + fail-closed close (CYP-833) teeth, driven against a fake socket (no real
// remote; live-arming gated). Covers the happy path, the client-advisory failure arms, and the fail-closed
// terminal-vs-reconnectable distinction (the CYP-833 security core).
import { describe, it, expect } from 'vitest'
import { connectRemoteHub } from './remoteHubConnector'
import { FakeSocketHub } from '../net/testing/fakeSocket'
import { Backoff } from '../net/backoff'
import type { HubDescriptor, HubIssuerTrust } from '../types/generated/contract'

const desc = (issuerTrust: HubIssuerTrust | null | undefined): HubDescriptor => ({
  hubId: 'h', name: 'H', online: true, defaultPort: 443, lastSeen: 0, dhPubKey: '', issuerTrust,
})

function wire(descriptor: HubDescriptor) {
  const hub = new FakeSocketHub()
  const conn = connectRemoteHub(descriptor, 'wss://hub/ws', {
    factory: hub.factory,
    schedule: hub.runNow,
    backoff: new Backoff(),
  })
  return { hub, conn }
}

describe('CYP-807-A5 RemoteHubConnector — progression', () => {
  it('★ TRUSTED: dial (on construct) → open → handshake → trust-check → connected', () => {
    const { hub, conn } = wire(desc('TRUSTED'))
    expect(conn.machine.getState()).toEqual({ phase: 'dialing' })
    hub.last().emitOpen()
    expect(conn.machine.getState()).toEqual({ phase: 'connected' })
  })

  it("★ NOT_TRUSTED → failed('issuer-not-trusted') at trust-check, socket deliberately closed (blocked advisory)", () => {
    const { hub, conn } = wire(desc('NOT_TRUSTED'))
    const s = hub.last()
    s.emitOpen()
    expect(conn.machine.getState()).toEqual({ phase: 'failed', cause: 'issuer-not-trusted' })
    expect(s.closed).toBe(true)
  })

  it("★ REMOTE_NOT_CONFIGURED → failed('remote-not-configured') (actionable arm)", () => {
    const { hub, conn } = wire(desc('REMOTE_NOT_CONFIGURED'))
    hub.last().emitOpen()
    expect(conn.machine.getState()).toEqual({ phase: 'failed', cause: 'remote-not-configured' })
  })

  it("★ close BEFORE open (while dialing) → failed('connect-refused'), NOT a reconnect (never established)", () => {
    // Even a known-reconnectable code (1006) during dialing is a refused dial, not a droppable established connection.
    const { hub, conn } = wire(desc('TRUSTED'))
    const before = hub.sockets.length
    hub.last().emitClose(1006)
    expect(conn.machine.getState()).toEqual({ phase: 'failed', cause: 'connect-refused' })
    expect(hub.sockets.length).toBe(before) // no reconnect from a pre-open close
  })
})

describe('CYP-807-A5 RemoteHubConnector — fail-closed close (CYP-833)', () => {
  it('★ established then 1008 → lost (terminal, no reconnect)', () => {
    const { hub, conn } = wire(desc('TRUSTED'))
    hub.last().emitOpen()
    const before = hub.sockets.length
    hub.last().emitClose(1008)
    expect(conn.machine.getState()).toEqual({ phase: 'lost' })
    expect(hub.sockets.length).toBe(before)
  })

  it('★ established then UNKNOWN code → lost (FAIL-CLOSED, no reconnect)', () => {
    // MUT (impl): flip isTerminalClose to fail-open → this becomes reconnecting + a re-dial; both assertions red.
    const { hub, conn } = wire(desc('TRUSTED'))
    hub.last().emitOpen()
    const before = hub.sockets.length
    hub.last().emitClose(4999)
    expect(conn.machine.getState()).toEqual({ phase: 'lost' })
    expect(hub.sockets.length).toBe(before) // an unknown drop must NOT reconnect
  })

  it('★ established then 1006 (known-reconnectable) → reconnecting + a fresh re-dial, then reconnects', () => {
    const { hub, conn } = wire(desc('TRUSTED'))
    hub.last().emitOpen()
    const before = hub.sockets.length
    hub.last().emitClose(1006) // reconnectable → reconnecting; runNow makes the scheduled re-dial synchronous
    expect(hub.sockets.length).toBe(before + 1) // a new socket was dialed
    expect(conn.machine.getState()).toEqual({ phase: 'dialing' })
    hub.last().emitOpen()
    expect(conn.machine.getState()).toEqual({ phase: 'connected' }) // reconnected
  })

  it('★ established then a NO-CODE close (undefined) → reconnecting + re-dial (transient, Tester2 baseline)', () => {
    const { hub, conn } = wire(desc('TRUSTED'))
    hub.last().emitOpen()
    const before = hub.sockets.length
    hub.last().emitClose() // no code → transient → reconnectable
    expect(hub.sockets.length).toBe(before + 1)
    expect(conn.machine.getState()).toEqual({ phase: 'dialing' })
  })

  it('★ teardown close() → socket closed; a subsequent drop is NOT mapped to a failure', () => {
    const { hub, conn } = wire(desc('TRUSTED'))
    hub.last().emitOpen()
    const s = hub.last()
    conn.close()
    expect(s.closed).toBe(true)
    s.emitClose(1006) // post-teardown → ignored (no drop mapping)
    expect(conn.machine.getState()).toEqual({ phase: 'connected' })
  })
})
