// CYP-826 (CYP-807-A2) — the Zone-2 connect-failure REGION host (parity with Compose RemoteFailureView). Renders EXACTLY
// ONE failure arm for a terminal-negative RemoteConnState, cause-distinct (no collapse "Verbindung fehlgeschlagen" copy).
// Placement per uiux2 CYP-823 §3/§4. It is Zone 2 ONLY — never a status badge (Zone 1 = HubTrustBadge/tier lives in the
// chrome, mounted elsewhere); the two zones never mix in one element (CYP-823 §7 tooth 1).
//
// ★ TRIGGER = terminal-negative = `failed(cause)` ∪ terminal-`lost` (CYP-822-A2). A transient/reconnectable state
// (idle/dialing/handshake/trust-check/connected/**reconnecting**) is NOT a failure → renders nothing. `reconnecting` is
// the polite/retryable counterpart of a drop and must NEVER read as a terminal stop (coordinator-decided; uiux2 flag-2).
//
// ★ ARM-SWITCH, ADDITIVE: today only the ISSUER arm has a parked leaf (IssuerNotTrustedBlock, CYP-805). The other cause
// arms (connect-refused/handshake-fail transport · security-tier · remote-not-configured actionable) and the terminal-
// `lost` arm land ADDITIVELY as their leaves are built (Auth/TrustChanged/Transport, CYP-807 follow-ups); the switch is
// exhaustive over ConnectFailureCause (assertNever), so a NEW cause fails to COMPILE until it is given an arm here — no
// silent gap. Until a leaf exists those outcomes render nothing in the region (flagged, not user-visible yet).
//
// ★ SECURITY (CYP-805): the issuer arm passes ONLY the issuerTrust ENUM to the leaf — NEVER a self-asserted issuer id.
import { IssuerNotTrustedBlock } from './IssuerNotTrustedBlock'
import type { RemoteConnState } from '../state/remoteConnState'

export interface RemoteFailureViewProps {
  /** The current connect-progression state (from the CYP-822 machine). */
  state: RemoteConnState
}

export function RemoteFailureView({ state }: RemoteFailureViewProps) {
  // Only a terminal-negative outcome opens the region. `failed(cause)` dispatches per cause (arm-switch); the terminal
  // `lost` phase is its own future arm (no leaf yet). Everything else — including the transient `reconnecting` — is not
  // a failure and renders nothing.
  if (state.phase === 'failed') {
    switch (state.cause) {
      case 'issuer-not-trusted':
        // The cause deterministically implies the issuer verdict was NOT_TRUSTED (resolveTrustCheck emits this cause only
        // when issuerConnectDecision blocked). Pass the ENUM only — the leaf renders its terminal WARN block. NEVER an id.
        return <IssuerNotTrustedBlock issuerTrust="NOT_TRUSTED" />
      case 'remote-not-configured':
      case 'connect-refused':
      case 'handshake-fail':
      case 'security-tier':
        // Additive arms — no parked leaf yet. Structured so each slots in here without touching the issuer arm.
        return null
      default:
        // Exhaustiveness: a new ConnectFailureCause (e.g. a future 'issuer-revoked') fails to COMPILE here until it is
        // given an arm — the fail-closed guarantee that a new failure never silently shows nothing by omission.
        return assertNever(state.cause)
    }
  }
  // terminal `lost` = a future terminal-lost arm (no leaf yet); reconnecting / idle / dialing / handshake / trust-check /
  // connected are not failures → nothing.
  return null
}

function assertNever(x: never): never {
  throw new Error(`unhandled ConnectFailureCause arm: ${JSON.stringify(x)}`)
}
