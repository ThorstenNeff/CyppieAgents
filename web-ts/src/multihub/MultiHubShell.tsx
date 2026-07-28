// CYP-854 (CYP-807-A Multi-Hub M4) — the mount-host: wires the M1–M5 units into the app shell per the CYP-857 mount
// plan (docs/design/cyp857-m4-mount-plan.md). Holds the host orchestration: the active pointer, per-hub trust
// PROVENANCE, and the ONE-ACTIVE connection; renders Zone-1 (HubSwitcher, fed displayedTrust) + Zone-2
// (ConnectProgressionChrome, fed progressionStateFor of the active machine).
//
// ★ §3 RENDER-HONESTY PRE-ARMING (BINDING, PL-gated): nothing arms here. The HubConnector is INJECTED — production
// mounts the IDLE connector (opens an idle machine, never dials), the real dial connector arrives at arming (CYP-807-A5).
// Pre-arming: no observation is recorded → displayedTrust is UNKNOWN for every hub; the active machine rests at idle →
// progressionStateFor is null → NO progression renders. So a mounted-but-not-armed shell shows honest UNKNOWN / nothing
// — NEVER a false trusted / connected / live before the arming seam supplies real, observed data. Display-only,
// reversible behind the connector seam.
import { useEffect, useRef, useState } from 'react'
import { HubSwitcher } from './HubSwitcher'
import { createActiveHubConnection, type HubConnector, type ActiveHubConnection } from '../state/activeHubConnection'
import { createRemoteConnMachine, type RemoteConnState } from '../state/remoteConnState'
import { emptyTrustProvenance, displayedTrust, type TrustProvenance } from '../state/hubTrustProvenance'
import { progressionStateFor, ConnectProgressionChrome } from '../connector/ConnectProgressionChrome'
import { RemoteFailureView } from '../connector/RemoteFailureView'
import type { HubId } from '../net/hubRegistry'

/** Pre-arming connector: opens an IDLE machine that is never driven (no dial). progressionStateFor(idle) = null → no
 *  progression; and no observation is made → trust stays UNKNOWN. The real dial connector replaces this at arming. */
const idleConnector: HubConnector = () => ({ machine: createRemoteConnMachine(), close: () => {} })

export function MultiHubShell({
  initialActiveHubId,
  connector = idleConnector,
  onRetryHubs,
}: {
  /** The active hub at mount — mirrors the host's activeHubId (AuthGate/cfg.hubId). */
  initialActiveHubId: HubId
  /** How to open a live connection. Default = the pre-arming IDLE connector; the real dial is injected at arming. */
  connector?: HubConnector
  /** Retry a failed /api/cp/hubs hub-list load. */
  onRetryHubs?: () => void
}) {
  const [activeHubId, setActiveHubId] = useState<HubId>(initialActiveHubId)
  const [connState, setConnState] = useState<RemoteConnState>({ phase: 'idle' })
  // Per-hub observed-trust provenance. EMPTY pre-arming (no live observation yet) → displayedTrust = UNKNOWN everywhere.
  // The arming seam records observations here (recordObservation) once a real connection observes a hub's key.
  const provenanceRef = useRef<TrustProvenance>(emptyTrustProvenance)
  const ahcRef = useRef<ActiveHubConnection | null>(null)
  if (ahcRef.current === null) ahcRef.current = createActiveHubConnection(connector)

  // ONE-ACTIVE: on the active pointer changing, switch the live connection (tear down old, open new) and follow the new
  // machine's state. Non-optimistic: the switcher's active marker follows activeHubId, which we set on switch.
  useEffect(() => {
    const ahc = ahcRef.current
    if (ahc === null) return
    ahc.switchTo(activeHubId)
    const active = ahc.active()
    if (active === null) return
    setConnState(active.machine.getState())
    return active.machine.subscribe(setConnState)
  }, [activeHubId])

  // Tear the connection down on unmount (no leaked background connection).
  useEffect(() => () => ahcRef.current?.close(), [])

  // displayedTrust → the switcher's axis-a badge. Pre-arming (empty provenance) this is UNKNOWN for every hub; once
  // observed, the active hub shows live trust and a switched-away hub degrades to STALE (never cached-trusted).
  const trustFor = (hubId: HubId) => displayedTrust(provenanceRef.current, hubId, activeHubId)
  const onSwitch = async (hubId: HubId): Promise<void> => {
    setActiveHubId(hubId) // the effect performs the switchTo + re-subscribe; active marker follows this (non-optimistic)
  }
  const progression = progressionStateFor(connState)

  return (
    <div className="multihub-shell" data-testid="multihub-shell">
      <HubSwitcher activeHubId={activeHubId} onSwitch={onSwitch} onRetry={onRetryHubs ?? (() => {})} trustFor={trustFor} />
      {/* Zone-2 in-flight progression — only while the active connect is in an in-flight phase (idle/failed/lost → none). */}
      {progression !== null && <ConnectProgressionChrome state={progression} />}
      {/* Zone-2 terminal-failure region — self-gating: renders an arm ONLY on failed(cause) (e.g. IssuerNotTrustedBlock
          on failed(issuer-not-trusted)); pre-arming (idle) it renders nothing. A failure arm is fail-closed (a WARN
          block / refusal), never a false trusted/proceed — consistent with the §3 render-honesty gate. */}
      <RemoteFailureView state={connState} />
    </div>
  )
}
