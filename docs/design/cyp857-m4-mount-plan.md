# CYP-857 — M4 (CYP-854) Mount-Plan: wiring the multi-hub shell into the host

> Dev5 M4-prep. Companion to the M1–M5 integration/composition tooth (`web-ts/src/multihub/multiHubComposition.test.tsx`),
> which proves the unit **seams** fit before M4 mounts them. This doc is the **placement plan** so M4 is immediately
> buildable the moment the §4b weiche (live-arming gate) falls. **M4 itself stays gated** — this is the map, not the mount.

## 0. What exists (units, all merged or merge-GO'd) vs what M4 adds
| Unit | File | Role |
|---|---|---|
| M1 hub list | `state/hubList.ts` · `state/hubListStore.ts` | the known hubs (stub-injected `HubDescriptor[]`); `{hubs, loaded, loadError}` |
| M2 switcher | `multihub/HubSwitcher.tsx` | list render, non-optimistic switch, 4 axes; `HubTrustBadge` currently fed `trust={null}` |
| M3 conn-model | `state/activeHubConnection.ts` | ONE-ACTIVE lifecycle: `switchTo` teardown-old→open-new behind an injected `HubConnector` |
| M3 provenance | `state/hubTrustProvenance.ts` | `recordObservation` / `displayedTrust` — active=live, inactive-was-trusted→STALE |
| M5 chrome | `connector/ConnectProgressionChrome.tsx` | Zone-2 in-flight progression; `progressionStateFor(RemoteConnState)` |

**M4 adds ONLY the host orchestration** that the composition tooth currently plays in-test (the `MultiHubHarness`):
tracking `activeHubId`, bridging the switcher's `onSwitch`→`switchTo`, feeding `displayedTrust` into the switcher badge,
and rendering the progression chrome from the active machine's state. **No new leaf, no new copy** — pure placement.

## 1. Placement (which unit goes where)
- **Zone-1 always-visible chrome** (workspace bar): the `HubSwitcher` (M2). Each entry's `HubTrustBadge` is fed
  `displayedTrust(provenance, hub.hubId, activeHubId)` (M3) — the switcher gains a per-hub `trustFor(hubId)` input
  (today hardcoded `null`; M4 wires the real derivation). Reachability/lastSeen already render from the descriptor.
- **Zone-2 in-flight** (connect card, active hub only): `ConnectProgressionChrome` (M5) fed
  `progressionStateFor(activeMachine.getState())`; renders only while that returns non-null (idle/failed/lost → nothing
  here; failed/lost route to the CYP-823 Failure-Region, a separate host).
- **Active pointer**: `activeHubId` lives in the host (mirrors `AuthGate.activeHubId` / `cfg.hubId`); the switcher's
  active marker + all `displayedTrust` calls key on it. Switch = non-optimistic (follows the server-confirmed pointer).

## 2. The orchestration seam (what M4 wires — validated by the composition tooth)
```
setHubList(descriptors)                       // M1: stub-injected today; real GET /api/cp/hubs = arming
onSwitch(hubId) = async () => {               // M2→M3 seam (bridges Promise→void)
  activeHubId = hubId                         //   advance the active pointer (non-optimistic: server-confirmed)
  activeHubConnection.switchTo(hubId)         //   M3: tears down old, opens new (ONE-ACTIVE, no background conn)
}
onObserve(hubId, trust, at) = recordObservation(...)   // M3: axis-a trust observed over the LIVE connection (= arming)
switcher badge trust = displayedTrust(provenance, hubId, activeHubId)   // active=live, switched-away-was-trusted=STALE
progression state    = progressionStateFor(activeConn.machine.getState())
```

## 3. ★ Render-honesty PRE-ARMING (§2b / CYP-824 discipline) — load-bearing
Until the arming seam supplies **real observations**, M4 mounts every trust leaf **neutral-default**:
- `recordObservation` is NOT called pre-arming (no live observation exists) → `displayedTrust` returns **UNKNOWN**
  for every hub (active = "not yet observed"; inactive-never-observed = UNKNOWN). This equals today's `trust={null}`
  → so **mounting M4 pre-arming changes nothing visible** — no hub renders `trusted`/`stale` before it is observed.
- `progressionStateFor` is driven only by the real machine; against the gated stub it rests at `idle` → the chrome
  renders nothing. No optimistic `connected`/`●` before a real connect.
- **Invariant (the M4 render-honesty tooth):** a leaf mounted-but-not-armed renders **UNKNOWN / nothing**, NEVER
  `trusted`/`native`/`connected`. The render is advisory; the server stays the authority (client-gate ≠ boundary).

## 4. The HubConnector (real dial) — the arming seam, NOT M4
`createActiveHubConnection(connector)` takes an injected `HubConnector: (hubId) => {machine, close}`. Today: the fake
(FakeRemoteConnector-driven) in tests. At arming: the real connector opens the machine-socket + the CYP-844 `statusFeed`
for the hub and drives the machine from the wire. M4 mounts against the injected seam; the concrete dial lands with
CYP-828/§9.3 (CYP-842's bounded-reconnect + the frozen-machine first-vs-reconnect-dial question ride the same arming cluster).

## 5. Deferred / open (do NOT build in M4-prep)
- **Real observation source** for `recordObservation` (axis-a dhPubKey-vs-pinned comparison over the live conn) = arming.
- **STALE lapse timing** = immediate-on-switch (ratified); a future freshness window is an additive threshold behind the
  `observedAt` provenance already carried (build-the-seam-not-the-window).
- **`authenticating`** progression state = Compose-only (Backend2 ruling) — never a web render.
- **Failure-Region host** (failed/lost, actionable arm) = CYP-823, a separate mount from the progression chrome.
