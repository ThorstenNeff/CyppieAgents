// CYP-425 (App-Assembly) — the runtime config bundle the assembly needs: where the API/WS live and the operator
// posture. Injectable socket deps let the whole app be driven by fake sockets in tests. (CYP-444: the PO identity
// is no longer here — it comes from the typed roster's role==PO, not a CYPPIE_PO_AGENT_ID config guess.)
import { apiBaseUrl, wsBaseUrl, operatorToken, isOperatorServe } from '../platform/appConfig'
import type { SocketFactory, Scheduler } from '../net/reconnectingSocket'
import { hubRegistryOf, UnknownHubError, type HubId, type HubEndpoint, type HubEndpointRegistry } from '../net/hubRegistry'

/** CYP-800: the single local hub's stable id. Multi-hub (real hubIds from the issuer contract) is a follow-up
 *  (CYP-798); today one hub carries this constant key so the per-hubId shape is already in place. */
export const LOCAL_HUB_ID: HubId = 'local'

export interface HubConfig {
  /** CYP-800 (N1.1): the active hub's id — the key for endpoint choice, credential routing, and 401 isolation. */
  hubId: HubId
  /** CYP-800 (N1.1): the per-hubId endpoint registry — the SINGLE source of where each hub lives. apiBase/wsBase
   *  below are a derived VIEW of the active hub's entry, never an independent parallel global. */
  registry: HubEndpointRegistry
  /** REST base (http[s]) for the ACTIVE hub — derived from `registry.endpointFor(hubId)`, not set independently. */
  apiBase: string
  /** WebSocket base (ws[s]) for the ACTIVE hub — derived from `registry.endpointFor(hubId)`. */
  wsBase: string
  /** the `?token=` for WS + the Bearer for REST on the operator serve; '' on the member serve (cookie carries REST). */
  token: string
  /** operator serve (operator-token global present) → operator surfaces (shell, ACL writes, mode toggle) are live. */
  operator: boolean
}

/**
 * CYP-800: build a HubConfig around a registry, deriving apiBase/wsBase from the active hub's entry so the two can
 * never drift from the registry (the single source). Shared by readHubConfig (prod) and test fixtures. Fail-closed:
 * an active hubId absent from the registry is a construction bug, surfaced loudly rather than silently defaulted.
 */
export function hubConfigFrom(
  registry: HubEndpointRegistry,
  hubId: HubId,
  auth: { token: string; operator: boolean },
): HubConfig {
  const endpoint = registry.endpointFor(hubId)
  if (endpoint === null) {
    // Consumer-layer fail-closed: the registry returned null (lookup miss); a config that MUST have the active hub's
    // bases turns that into a loud named throw rather than silently defaulting to some global (CYP-800 N1).
    throw new UnknownHubError(hubId)
  }
  return { hubId, registry, apiBase: endpoint.apiBase, wsBase: endpoint.wsBase, token: auth.token, operator: auth.operator }
}

/** CYP-800: the common single-hub case — one endpoint under one hubId. Used by readHubConfig and tests so a fixture
 *  never hand-assembles the registry + derived fields (and so they cannot drift). */
export function singleHubConfig(opts: {
  hubId?: HubId
  endpoint: HubEndpoint
  token: string
  operator: boolean
}): HubConfig {
  const hubId = opts.hubId ?? LOCAL_HUB_ID
  return hubConfigFrom(hubRegistryOf({ [hubId]: opts.endpoint }), hubId, { token: opts.token, operator: opts.operator })
}

/**
 * CYP-800 (N1.1): derive the active hub's config from the endpoint registry — registry-KEYED (takes hubId), reads
 * NO single-valued module globals. The hub state is the registry's, not an ambient global; fail-closed via
 * hubConfigFrom (UnknownHubError) if the hubId is absent. This is the shape the migration establishes: hub identity
 * flows by key, not by a single global endpoint.
 */
export function readHubConfig(hubId: HubId, registry: HubEndpointRegistry, auth: { token: string; operator: boolean }): HubConfig {
  return hubConfigFrom(registry, hubId, auth)
}

/**
 * CYP-800 (N1.1): the ONE place the single-valued env globals (apiBaseUrl/wsBaseUrl/operatorToken/isOperatorServe)
 * are read — to SEED the local hub's registry entry at boot. Everything downstream keys on the returned registry,
 * not these globals. Single-hub today (one 'local' entry); multi-hub (CYP-798) adds entries here without touching
 * any consumer. Returns a ready HubConfig via the registry-keyed readHubConfig, so the derived apiBase/wsBase are a
 * view of the seeded entry, never a parallel global.
 */
export function bootstrapLocalHub(): HubConfig {
  const registry = hubRegistryOf({ [LOCAL_HUB_ID]: { apiBase: apiBaseUrl(), wsBase: wsBaseUrl() } })
  return readHubConfig(LOCAL_HUB_ID, registry, { token: operatorToken() ?? '', operator: isOperatorServe() })
}

/** Injectable socket plumbing — real by default, fakes in tests (so App renders with no live WebSocket). */
export interface SocketDeps {
  factory?: SocketFactory
  schedule?: Scheduler
  /** CYP-881 (DARK): inject the ws-ticket mint (tests only). Production uses the real mintWsTicket(apiBase). Only
   *  consulted when the CYPPIE_WS_TICKET flag is ON; ignored on the default `?token=` path. */
  mintTicket?: (apiBase: string) => Promise<string>
}
