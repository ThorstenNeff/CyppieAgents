// CYP-800 (Modell-2 Client Fundament, N1.1) — the per-hubId endpoint registry that REPLACES the single-value global
// apiBase/wsBase (hubConfig.ts). Modell 2 talks to N hubs; every hub-bound thing (endpoint choice, credential
// routing, 401 isolation) keys on a `hubId`. This module holds ONLY the endpoint dimension (N1.1) — the per-hub
// TRUST state (N3: UNKNOWN/PENDING/TRUSTED/REJECTED/STALE), the audience-bound credential (N2), and the
// cause-honest error split (N4a) are gated on the issuer/trust contract (CYP-798) and are NOT built here.
//
// Honesty core (spec §N1): `endpointFor` returns null for an unknown hub — a fail-closed miss, NEVER a fallback to
// some ambient global. A silent global fallback is exactly the cross-hub confusion (wrong endpoint / wrong
// credential / wrong state) N1 exists to prevent: we would rather resolve NOTHING than resolve the wrong hub.

/** The stable key for everything hub-scoped. Kept a plain string here (the issuer-anchored identity is CYP-798). */
export type HubId = string

/**
 * CYP-800: the CONSUMER-layer fail-closed error. `endpointFor` itself returns null (a lookup miss is data, not an
 * exception); a consumer that REQUIRES an endpoint (e.g. readHubConfig deriving the active hub's bases) turns that
 * null into this named throw, so a missing hub fails loudly at the point of use instead of silently defaulting. The
 * two layers stay distinct: null at the registry boundary, throw at the consumer boundary — both fail-closed.
 */
export class UnknownHubError extends Error {
  constructor(readonly hubId: HubId) {
    super(`CYP-800: hub '${hubId}' is not in the endpoint registry`)
    this.name = 'UnknownHubError'
  }
}

/** Where one hub lives — the two bases that used to be single globals. */
export interface HubEndpoint {
  /** REST base (http[s]) for this hub's /api/*. */
  readonly apiBase: string
  /** WebSocket base (ws[s]) for this hub's /ws/*. */
  readonly wsBase: string
}

/**
 * A read-only resolver from hubId → endpoint. The single source of "where does hub X live"; the derived
 * convenience fields on HubConfig (apiBase/wsBase for the active hub) are a VIEW of this, never a parallel global.
 */
export interface HubEndpointRegistry {
  /** The registered hubIds (insertion order). */
  hubIds(): readonly HubId[]
  /** The endpoint for a hubId, or null if that hub is not registered — fail-closed, never a fallback global. */
  endpointFor(hubId: HubId): HubEndpoint | null
}

/**
 * Build a registry from a fixed set of entries. In the single-hub foundation this holds exactly one entry (the local
 * hub); the SHAPE is already per-hubId so multi-hub is an additive follow-up, not a re-plumb.
 */
export function hubRegistryOf(entries: Readonly<Record<HubId, HubEndpoint>>): HubEndpointRegistry {
  // copy so a later mutation of the caller's object cannot silently re-point a hub.
  const map = new Map<HubId, HubEndpoint>(Object.entries(entries).map(([id, ep]) => [id, { ...ep }]))
  return {
    hubIds: () => [...map.keys()],
    endpointFor: (hubId) => {
      const ep = map.get(hubId)
      return ep ? { ...ep } : null // unknown hub → null, NOT a guessed global
    },
  }
}
