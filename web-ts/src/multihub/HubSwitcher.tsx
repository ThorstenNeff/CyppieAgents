// CYP-852 (CYP-807-A Multi-Hub M2) — the hub switcher: the N known hubs (M1 useHubListStore) as a LIST (not a
// <select> — a list entry can carry per-hub badges + a11y that <option> cannot). Reuses the ProjectSwitcher/CYP-651
// switch BEHAVIOR: NON-OPTIMISTIC (the active marker follows the server-confirmed `activeHubId` prop, never the click),
// disabled while a switch is pending, switch-to-active = no-op. The real switch effect (teardown/setup of the new
// active hub, CYP-755 §3) is the injected `onSwitch` — an arming seam, not built here.
//
// ★ FOUR DISTINCT AXES PER ENTRY, NEVER CONFLATED (UIUX2 spec §1, ruling 7dda489f — Zone-clean):
//   • name/identity — `name` (+ hubId as the stable key)
//   • reachability  — `online` → a NEUTRAL Online/Offline marker (reachability ≠ trust; offline ≠ untrusted)
//   • hub-key-trust (axis a) — HubTrustBadge, fed trust=null → UNKNOWN (pre-arming neutral, §2b): the switcher NEVER
//     wires the badge to a live trust decision; real observed trust arrives at the arming seam (M3).
//   • freshness — `lastSeen` → "zuletzt gesehen" HH:MM.
// axis-c (issuerTrust) is DELIBERATELY ABSENT — it is a whole Zone-2 connect verdict (M4 failure region), never a
// switcher badge (splitting one axis-c field across two zones would be incoherent). Tier is the active-header, not here.
//
// Empty ≠ Load-Error ≠ Unknown (three distinct store outcomes): a failed /api/cp/hubs load → Error+Retry (never a
// misleading "no hubs"); a successful zero-hub result → honest empty; not-loaded-yet → render nothing.
import { useState } from 'react'
import { useHubListStore } from '../state/hubListStore'
import { HubTrustBadge } from '../comm/HubTrustBadge'
import { LoadErrorRetry } from '../ui/LoadErrorRetry'
import { formatHandoffSince } from '../agentview/handoffBannerModel'
import type { HubId } from '../net/hubRegistry'
import type { HubTrustState } from '../connector/hubTrustModel'

export function HubSwitcher({
  activeHubId,
  onSwitch,
  onRetry,
  trustFor,
}: {
  /** The server-confirmed active hub — the active marker follows THIS, never an in-flight click (non-optimistic). */
  activeHubId: HubId
  /** Switch the active hub. The real teardown/setup (CYP-755 §3) lives behind this injected seam, not in the switcher. */
  onSwitch: (hubId: HubId) => Promise<void>
  /** Retry a failed /api/cp/hubs load. */
  onRetry: () => void
  /** CYP-854 (M4): the axis-a trust to display per hub, from the host's observation provenance (displayedTrust). ABSENT
   *  ⇒ null ⇒ UNKNOWN (fail-closed pre-arming §2b): the switcher never fabricates trust; the host feeds observed/STALE. */
  trustFor?: (hubId: HubId) => HubTrustState
}) {
  const { hubs, loaded, loadError } = useHubListStore()
  const [pending, setPending] = useState(false)

  // Fail-closed, distinct outcomes: a failed load is Error+Retry, NEVER an empty "no hubs".
  if (loadError) return <LoadErrorRetry testId="hub-switcher.error" onRetry={onRetry} />
  if (!loaded) return null // unknown — not loaded yet; render nothing rather than a confident empty
  if (hubs.length === 0)
    return (
      <p className="hub-switcher-empty" data-testid="hub-switcher.empty">
        Keine Hubs
      </p>
    )

  const pick = (hubId: HubId) => {
    if (hubId === activeHubId) return // switch-to-active = no-op
    setPending(true)
    void onSwitch(hubId).finally(() => setPending(false))
  }

  return (
    <nav className="hub-switcher" data-testid="hub-switcher" aria-label="Aktiven Hub wechseln">
      <ul className="hub-switcher-list">
        {hubs.map((h) => {
          const active = h.hubId === activeHubId
          return (
            <li key={h.hubId}>
              <button
                type="button"
                className={`hub-switcher-entry${active ? ' hub-switcher-entry-active' : ''}`}
                data-testid={`hub-switcher.entry.${h.hubId}`}
                // NON-OPTIMISTIC: aria-current follows the server-confirmed activeHubId, not the click.
                aria-current={active ? 'true' : undefined}
                // A pending switch disables the whole list (non-optimistic — no in-flight re-point). The active hub is
                // NOT per-item-disabled: switch-to-active is a no-op via the pick() guard (mirrors ProjectSwitcher).
                disabled={pending}
                onClick={() => pick(h.hubId)}
              >
                <span className="hub-switcher-name" data-testid={`hub-switcher.name.${h.hubId}`}>
                  {h.name}
                </span>
                {/* reachability — a SEPARATE, neutral marker (never the trust badge; offline ≠ untrusted). */}
                <span
                  className={`hub-switcher-reach hub-switcher-reach-${h.online ? 'online' : 'offline'}`}
                  data-testid={`hub-switcher.reach.${h.hubId}`}
                  data-online={h.online ? 'true' : 'false'}
                >
                  {h.online ? 'Online' : 'Offline'}
                </span>
                {/* freshness — "zuletzt gesehen" (HH:MM), a SEPARATE axis from reachability and trust. */}
                <span className="hub-switcher-lastseen" data-testid={`hub-switcher.lastSeen.${h.hubId}`}>
                  zuletzt gesehen {formatHandoffSince(h.lastSeen)}
                </span>
                {/* axis-a hub-key trust — from the host's observation provenance (displayedTrust); absent ⇒ UNKNOWN
                    (fail-closed pre-arming §2b). The switcher never derives trust; it only displays what it is fed. */}
                <HubTrustBadge hubId={h.hubId} trust={trustFor?.(h.hubId) ?? null} />
              </button>
            </li>
          )
        })}
      </ul>
    </nav>
  )
}
