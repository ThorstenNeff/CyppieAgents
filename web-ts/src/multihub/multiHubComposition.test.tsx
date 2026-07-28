// @vitest-environment jsdom
// CYP-854 (prep) — the M1–M5 COMPOSITION tooth. Proves the multi-hub units WIRE end-to-end (the seams fit: types,
// signatures, data flow) BEFORE M4 mounts them in the real host. No host/App mount — an in-test harness plays M4's
// orchestration role (track activeHubId, drive switchTo, record observations, derive displayedTrust/progressionState),
// and the two Zone leaves are rendered with the COMPOSED outputs to prove the render seams concretely.
//
// The seams under test (each fails the composition if it drifts):
//   • store (M1)            → the hub list a switch selects from
//   • onSwitch ↔ switchTo   → HubSwitcher's onSwitch:(hubId)=>Promise<void> bridges to activeHubConnection.switchTo (M2↔M3)
//   • switchTo (M3)         → ONE-ACTIVE: switching tears down the prior connection (no background conn)
//   • recordObservation/displayedTrust (M3) → active=live, switched-away-was-trusted=STALE (the MC-2 degradation)
//   • displayedTrust → HubTrustBadge.trust (M3→M2 leaf): a HubTrustState feeds the badge
//   • machine.getState():RemoteConnState → progressionStateFor → ProgressionState → ConnectProgressionChrome (M3→M5)
import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup, fireEvent } from '@testing-library/react'
import { useHubListStore } from '../state/hubListStore'
import { emptyHubList } from '../state/hubList'
import { createActiveHubConnection } from '../state/activeHubConnection'
import { createRemoteConnMachine } from '../state/remoteConnState'
import { FakeRemoteConnector } from '../state/testing/fakeRemoteConnector'
import { emptyTrustProvenance, recordObservation, displayedTrust } from '../state/hubTrustProvenance'
import type { TrustProvenance } from '../state/hubTrustProvenance'
import type { HubTrustState } from '../connector/hubTrustModel'
import { progressionStateFor, ConnectProgressionChrome } from '../connector/ConnectProgressionChrome'
import { HubTrustBadge } from '../comm/HubTrustBadge'
import { HubSwitcher } from './HubSwitcher'
import type { HubDescriptor } from '../types/generated/contract'
import type { HubId } from '../net/hubRegistry'

const hub = (hubId: string): HubDescriptor => ({
  hubId,
  name: hubId.toUpperCase(),
  online: true,
  defaultPort: 8443,
  lastSeen: 1_700_000_000_000,
  dhPubKey: `pk-${hubId}`,
})

/** Stands in for M4's orchestration: owns the active pointer + provenance, bridges onSwitch→switchTo, and derives the
 *  values the Zone leaves consume. This is TEST scaffolding, not the production host (that is M4, weiche-gated). */
class MultiHubHarness {
  activeHubId: HubId = ''
  provenance: TrustProvenance = emptyTrustProvenance
  readonly connectors: Record<string, FakeRemoteConnector> = {}
  readonly ahc = createActiveHubConnection((hubId) => {
    const machine = createRemoteConnMachine()
    const fake = new FakeRemoteConnector()
    this.connectors[hubId] = fake
    const unsub = fake.subscribe(machine.send)
    return { machine, close: unsub }
  })
  /** the M2↔M3 seam: HubSwitcher.onSwitch (async) bridges to the sync switchTo + advances the active pointer. */
  onSwitch = async (hubId: HubId): Promise<void> => {
    this.activeHubId = hubId
    this.ahc.switchTo(hubId)
  }
  observe(hubId: HubId, trust: HubTrustState, at: number): void {
    this.provenance = recordObservation(this.provenance, hubId, trust, at)
  }
  trustFor(hubId: HubId): HubTrustState {
    return displayedTrust(this.provenance, hubId, this.activeHubId)
  }
  progression() {
    const a = this.ahc.active()
    return a ? progressionStateFor(a.machine.getState()) : null
  }
}

afterEach(() => {
  cleanup()
  useHubListStore.setState({ ...emptyHubList })
})

describe('CYP-854 (prep) — M1–M5 composition: the units wire end-to-end before M4 mounts them', () => {
  it('★ end-to-end: switch A→B degrades A to STALE, B stays live-UNKNOWN, progression tracks the active machine', async () => {
    useHubListStore.getState().setHubList([hub('A'), hub('B')]) // M1
    const h = new MultiHubHarness()

    await h.onSwitch('A') // selection → switchTo opens A
    const fa = h.connectors['A']
    fa.dial()
    fa.openHandshake()
    fa.completeHandshake()
    fa.evaluateTrustFromIssuer('TRUSTED') // → connected
    h.observe('A', 'TRUSTED', 1000) // axis-a observed over the live connection
    expect(h.trustFor('A')).toBe('TRUSTED') // active hub → live observed trust
    expect(h.progression()).toBe('connected') // machine → progressionStateFor seam

    await h.onSwitch('B') // switch away from A
    expect(h.trustFor('A')).toBe('STALE') // A inactive, was trusted → STALE (never cached-trusted)
    expect(h.trustFor('B')).toBe('UNKNOWN') // B active, not yet observed → fail-closed unknown
    h.connectors['B'].dial()
    expect(h.progression()).toBe('dialing') // progression now tracks B's machine, not A's
  })

  it('★ ONE-ACTIVE across the composition: switching TEARS DOWN the prior hub connection (no background conn)', async () => {
    const h = new MultiHubHarness()
    await h.onSwitch('A')
    const aMachine = h.ahc.active()!.machine
    h.connectors['A'].dial()
    h.connectors['A'].openHandshake()
    h.connectors['A'].completeHandshake()
    h.connectors['A'].evaluateTrustFromIssuer('TRUSTED') // A connected
    expect(aMachine.getState().phase).toBe('connected')
    await h.onSwitch('B') // tears down A (unsubscribes its connector)
    // A's connector is detached → driving it no longer moves A's (old) machine: it holds its last state, no background churn.
    h.connectors['A'].reset() // would idle a STILL-LIVE machine; A is torn down, so this reaches nobody
    expect(aMachine.getState().phase).toBe('connected') // unchanged → A really was torn down (no background connection)
    expect(h.ahc.active()!.hubId).toBe('B')
  })

  it('★ render seam: displayedTrust feeds the HubTrustBadge (STALE renders) — the M3→M2 leaf contract holds', () => {
    const p = recordObservation(emptyTrustProvenance, 'A', 'TRUSTED', 1)
    const trust = displayedTrust(p, 'A', 'B') // A inactive, was trusted → STALE
    const { getByTestId } = render(<HubTrustBadge hubId="A" trust={trust} />)
    expect(getByTestId('hub.trust.A.stale')).toBeTruthy()
  })

  it('★ render seam: progressionStateFor feeds the ConnectProgressionChrome — the M3→M5 leaf contract holds', () => {
    const state = progressionStateFor({ phase: 'trust-check' })
    expect(state).toBe('trust-check')
    const { getByTestId } = render(<ConnectProgressionChrome state={state!} />)
    expect(getByTestId('remote.progression.trust-check')).toBeTruthy()
  })

  it('★ selection seam: clicking a HubSwitcher entry drives onSwitch → switchTo (store→switcher→connection)', () => {
    useHubListStore.getState().setHubList([hub('A'), hub('B')])
    const h = new MultiHubHarness()
    h.activeHubId = 'A'
    h.ahc.switchTo('A')
    const { getByTestId } = render(<HubSwitcher activeHubId="A" onSwitch={h.onSwitch} onRetry={() => {}} />)
    fireEvent.click(getByTestId('hub-switcher.entry.B')) // pick B → onSwitch('B') runs sync up to switchTo
    expect(h.ahc.active()?.hubId).toBe('B') // the switcher selection reached the connection controller
  })
})
