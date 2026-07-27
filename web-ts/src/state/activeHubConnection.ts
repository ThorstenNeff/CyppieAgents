// CYP-853 (Multi-Hub M3) — the switch-first / ONE-ACTIVE-HUB connection controller (CYP-748-Q4 ratified). The active
// pointer drives EXACTLY ONE live connection: one createRemoteConnMachine (CYP-822/826) + one statusFeed (CYP-844)
// behind an injected connector seam. Switching TEARS DOWN the previous hub's connection before opening the new one —
// inactive hubs hold NO live background connection (resource-safe, CYP-611 sizing; and the precondition for the
// immediate STALE lapse in hubTrustProvenance.ts — no connection ⇒ no observation).
//
// The connector is INJECTED: in tests it's a fake that hands back a machine driven by FakeRemoteConnector; the real
// dial (socket + statusFeed over the wire) is the ARMING seam (CYP-807-A5), not wired here. This controller owns only
// the ONE-ACTIVE lifecycle, not the transport.
import type { RemoteConnMachine } from './remoteConnState'
import type { HubId } from '../net/hubRegistry'

/** A single hub's live connection. `close()` tears it down (socket/feed closed, no further reconnect). */
export interface HubConnectionHandle {
  readonly machine: RemoteConnMachine
  close(): void
}

/** How to OPEN a live connection for a hub. Real = dial + statusFeed (arming); test/stub = FakeRemoteConnector-driven. */
export type HubConnector = (hubId: HubId) => HubConnectionHandle

export interface ActiveHubConnection {
  /** The currently active hub + its machine, or null before the first connect. */
  active(): { readonly hubId: HubId; readonly machine: RemoteConnMachine } | null
  /**
   * Make `hubId` the active hub: tear down the previous hub's live connection FIRST (no background connection survives
   * to an inactive hub), then open the new one. Switching to the already-active hub is a no-op (the live connection is
   * NOT torn down and re-dialed — mirrors the switcher's switch-to-active no-op).
   */
  switchTo(hubId: HubId): void
  /** Tear down the active connection entirely (App unmount). */
  close(): void
}

export function createActiveHubConnection(connector: HubConnector): ActiveHubConnection {
  let current: { hubId: HubId; handle: HubConnectionHandle } | null = null

  return {
    active: () => (current === null ? null : { hubId: current.hubId, machine: current.handle.machine }),
    switchTo: (hubId) => {
      if (current !== null && current.hubId === hubId) return // switch-to-active = no-op (do NOT tear down + re-dial)
      current?.handle.close() // tear down the OLD first — exactly one live connection at a time
      current = { hubId, handle: connector(hubId) }
    },
    close: () => {
      current?.handle.close()
      current = null
    },
  }
}
