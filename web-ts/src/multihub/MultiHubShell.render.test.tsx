// @vitest-environment jsdom
// CYP-854 (M4) — the BINDING render-honesty gate (PL): a mounted-but-not-armed shell must render honest UNKNOWN /
// nothing — NEVER a false trusted / connected / live before the arming seam supplies real observed data. Plus: driven
// (armed-in-test) it CAN show live progression, while trust stays honest until actually observed (liveness ≠ trust).
import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup, act } from '@testing-library/react'
import { MultiHubShell } from './MultiHubShell'
import { useHubListStore } from '../state/hubListStore'
import { emptyHubList } from '../state/hubList'
import { createRemoteConnMachine } from '../state/remoteConnState'
import { FakeRemoteConnector } from '../state/testing/fakeRemoteConnector'
import type { HubConnector } from '../state/activeHubConnection'
import type { HubDescriptor } from '../types/generated/contract'

const hub = (hubId: string): HubDescriptor => ({
  hubId,
  name: hubId.toUpperCase(),
  online: true,
  defaultPort: 8443,
  lastSeen: 1_700_000_000_000,
  dhPubKey: `pk-${hubId}`,
})

afterEach(() => {
  cleanup()
  useHubListStore.setState({ ...emptyHubList })
})

describe('CYP-854 (M4) — MultiHubShell render-honesty (the binding gate)', () => {
  it('★ PRE-ARMING: every hub trust badge is UNKNOWN and NO progression renders — never false-live/trusted/connected', () => {
    // THE gate. Default (idle) connector + no observations = pre-arming. MUT: feed trustFor a live value / mount a
    // connected machine / render progression optimistically → a .trusted or a remote.progression.* appears → reds.
    useHubListStore.getState().setHubList([hub('local'), hub('remote')])
    const { getByTestId, queryByTestId, container } = render(<MultiHubShell initialActiveHubId="local" />)
    expect(getByTestId('multihub-shell')).toBeTruthy()
    expect(getByTestId('hub-switcher')).toBeTruthy()
    // both hubs render axis-a trust as UNKNOWN — never a fabricated trusted/stale pre-observation:
    expect(getByTestId('hub.trust.local.unknown')).toBeTruthy()
    expect(getByTestId('hub.trust.remote.unknown')).toBeTruthy()
    expect(queryByTestId('hub.trust.local.trusted')).toBeNull()
    expect(queryByTestId('hub.trust.remote.trusted')).toBeNull()
    // no Zone-2 progression at all pre-arming (idle machine → progressionStateFor null):
    expect(container.querySelector('[data-testid^="remote.progression."]')).toBeNull()
  })

  it('★ an idle (never-driven) active connection renders NO connected/live marker (no optimistic connected)', () => {
    useHubListStore.getState().setHubList([hub('local')])
    const { queryByTestId } = render(<MultiHubShell initialActiveHubId="local" />)
    expect(queryByTestId('remote.progression.connected')).toBeNull()
    expect(queryByTestId('remote.progression.live')).toBeNull()
  })

  it('★ DRIVEN (armed-in-test): the shell shows live progression when the machine reaches connected — but trust stays UNKNOWN (liveness ≠ trust)', () => {
    // Proves the mount is real (it CAN render live when a connector drives it) AND that connection liveness does not
    // fabricate axis-a trust — the badge stays UNKNOWN until a real observation (arming), never conflated.
    const fake = new FakeRemoteConnector()
    const connector: HubConnector = () => {
      const machine = createRemoteConnMachine()
      const unsub = fake.subscribe(machine.send)
      return { machine, close: unsub }
    }
    useHubListStore.getState().setHubList([hub('local')])
    const { getByTestId, queryByTestId } = render(<MultiHubShell initialActiveHubId="local" connector={connector} />)
    act(() => {
      fake.dial()
      fake.openHandshake()
      fake.completeHandshake()
      fake.evaluateTrustFromIssuer('TRUSTED') // → connected
    })
    expect(getByTestId('remote.progression.connected')).toBeTruthy() // live progression renders when driven
    expect(getByTestId('remote.progression.live').textContent).toBe('●')
    // …but the axis-a trust badge is STILL UNKNOWN — connection liveness is not a trust observation:
    expect(getByTestId('hub.trust.local.unknown')).toBeTruthy()
    expect(queryByTestId('hub.trust.local.trusted')).toBeNull()
  })

  it('★ the terminal-failure region is mounted but SELF-GATED: pre-arming (idle) renders NO failure arm', () => {
    // MC-5 render-honesty: a mounted-not-armed failure leaf shows nothing (no false block before a real failure).
    useHubListStore.getState().setHubList([hub('local')])
    const { queryByTestId } = render(<MultiHubShell initialActiveHubId="local" />)
    expect(queryByTestId('remote.connect.error.issuerNotTrusted')).toBeNull()
  })

  it('★ DRIVEN: a failed(issuer-not-trusted) active connect mounts the IssuerNotTrustedBlock failure arm (leaf mounted for real failure)', () => {
    // The M4 leaf-mount: RemoteFailureView renders the issuer arm when the active machine reaches failed(issuer-not-trusted).
    // MUT: not mounting RemoteFailureView / mounting it only on a wrong phase → the arm never appears → reds.
    const fake = new FakeRemoteConnector()
    const connector: HubConnector = () => {
      const machine = createRemoteConnMachine()
      return { machine, close: fake.subscribe(machine.send) }
    }
    useHubListStore.getState().setHubList([hub('local')])
    const { getByTestId, queryByTestId } = render(<MultiHubShell initialActiveHubId="local" connector={connector} />)
    act(() => {
      fake.dial()
      fake.openHandshake()
      fake.completeHandshake()
      fake.evaluateTrustFromIssuer('NOT_TRUSTED') // → failed(issuer-not-trusted)
    })
    expect(getByTestId('remote.connect.error.issuerNotTrusted')).toBeTruthy()
    // and it is a failure arm, not a progression (failed → no in-flight progression):
    expect(queryByTestId('remote.progression.connected')).toBeNull()
  })

  it('★ DRIVEN: an in-flight phase shows the neutral progression, still no live marker before connected', () => {
    const fake = new FakeRemoteConnector()
    const connector: HubConnector = () => {
      const machine = createRemoteConnMachine()
      return { machine, close: fake.subscribe(machine.send) }
    }
    useHubListStore.getState().setHubList([hub('local')])
    const { getByTestId, queryByTestId } = render(<MultiHubShell initialActiveHubId="local" connector={connector} />)
    act(() => {
      fake.dial()
      fake.openHandshake() // → handshake
    })
    expect(getByTestId('remote.progression.handshake')).toBeTruthy()
    expect(queryByTestId('remote.progression.live')).toBeNull() // no ● before connected
  })
})
