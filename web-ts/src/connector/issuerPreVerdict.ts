// CYP-831 (CYP-807-A5) — the CLIENT-SIDE issuer advisory producer. Ratified no-oracle refinement (PL/Team-1,
// 2026-07-27): the issuer connect-verdict is CONSTRUCTED ON THE CLIENT from the HubDescriptor the client already holds
// (CYP-804's flat, pre-dial `issuerTrust`), NOT emitted by the hub. A hub-emitted connect-verdict would be an
// ISSUER-ORACLE (a probing attacker could read a hub's issuer-trust state) plus a new wire; the connect/tunnel reject
// stays UNIFORM / no-oracle (Model-2 §5a) and the SERVER connect-gate is the real fail-closed enforcement. This producer
// is ADVISORY-ONLY UX: it maps the descriptor to the sealed CYP-826 IssuerConnectVerdict so the Failure-View-Host can
// show the honest arm — it does NOT gate the connection (the server does).
//
// ★ Relationship to connector/issuerTrustModel.issuerConnectDecision (CYP-805, frozen): that function is the HARD-BLOCK
// render gate and blocks ONLY NOT_TRUSTED. This producer is a SUPERSET advisory verdict that ADDS the ACTIONABLE
// 'remote-not-configured' arm (PL-ratified for A5). They agree on NOT_TRUSTED; they intentionally differ on
// REMOTE_NOT_CONFIGURED (frozen decision proceeds → hard-block-gate view; this producer blocks-ACTIONABLE → advisory
// "configure the remote" arm). See the CYP-822 `issuerVerdictFor` note — this producer supersedes it for the connect flow.
import type { HubDescriptor, HubIssuerTrust } from '../types/generated/contract'
import type { IssuerConnectVerdict } from '../state/remoteConnState'

/**
 * Map a (pre-dial) issuer-trust value to the sealed advisory verdict. Two block causes (PL-ratified A5):
 *   • NOT_TRUSTED            → blocked('issuer-not-trusted')    — terminal: the hub declines the operator vouch.
 *   • REMOTE_NOT_CONFIGURED  → blocked('remote-not-configured') — actionable: no trusted issuer established; configure OOB.
 * TRUSTED / null / undefined (UNKNOWN) → proceed (advisory-neutral). ★ The client NEVER hard-blocks on unknown/absent —
 * that would be a false denial; the SERVER connect-gate fail-closes. Only positively-known block states map to arms.
 * A future 'issuer-revoked' is DEFERRED (no authoritative source today); when HubIssuerTrust grows it, add its blocked
 * arm here — the CYP-826 IssuerFailureCause taxonomy is already extensible for exactly this.
 */
export function issuerTrustToPreVerdict(issuerTrust: HubIssuerTrust | null | undefined): IssuerConnectVerdict {
  if (issuerTrust === 'NOT_TRUSTED') return { outcome: 'blocked', cause: 'issuer-not-trusted' }
  if (issuerTrust === 'REMOTE_NOT_CONFIGURED') return { outcome: 'blocked', cause: 'remote-not-configured' }
  return { outcome: 'proceed' }
}

/**
 * The A5 client advisory producer: derive the sealed IssuerConnectVerdict from a HubDescriptor the client already holds
 * (no hub round-trip, no oracle). Feeds the trust-check — a `blocked` verdict becomes RemoteConnState.failed(cause) via
 * remoteConnReduce's `trustEvaluated`; `proceed` lets the progression continue (the server gate still enforces).
 */
export function computeIssuerPreVerdict(descriptor: HubDescriptor): IssuerConnectVerdict {
  return issuerTrustToPreVerdict(descriptor.issuerTrust)
}
