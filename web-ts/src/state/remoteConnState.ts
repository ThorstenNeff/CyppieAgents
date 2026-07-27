// CYP-822 (CYP-807-A1, Multi-Hub / Option A) — the connect-progression state machine. This lifts web-ts from the flat
// `CommConnection` posture (live|connecting|offline|revoked, one local loopback socket — see state/hubReducers.ts:26)
// to a real MULTI-HUB connect PROGRESSION: idle → dialing → handshake → trust-check → connected, with the negative
// outcomes typed. It is PURE FE state logic (no transport, no React) — the honesty lives where it can be mutation-proven,
// not in a component (same mandate as hubReducers.ts). Buildable NOW against a fake/loopback connector; the real wire →
// event mapping (which raw signal produces which event) is the Backend/transport seam (CYP-807-A5 / Backend2 CYP-824)
// and is deliberately NOT this story.
//
// ★ AXIS DISCIPLINE — NEVER FOLD (coordinator-pinned, mirrored from connector/issuerTrustModel.ts:4). The trust-check
// step evaluates TWO independent axes and this module keeps them distinct in both the type (two separate failure causes)
// and the resolution logic (two separate branches):
//   • axis c = ISSUER trust ("does this hub trust your issuer's vouch?") → cause 'issuer-not-trusted'. Decided here via
//     the CANONICAL connector/issuerTrustModel.issuerConnectDecision — reused, never re-implemented (only NOT_TRUSTED
//     blocks). The client render is UX-completeness; the SERVER is the real gate (CYP-802).
//   • the security-TIER axis (connector/remoteSecurityTierModel) → cause 'security-tier'. This is NOT a client-computed
//     policy: web-ts is ALWAYS browser-gateway|unknown (never native), and the tier starts UNKNOWN until the transport
//     seam resolves it — so if the client derived a block from the raw RemoteSecurityTier, EVERY connect would fail on
//     the unknown-start. The tier block is therefore a connector-SUPPLIED verdict (TierGate), decided at the wire (A5),
//     mapped here. The reducer never inspects a RemoteSecurityTier value.
import { issuerConnectDecision, type HubIssuerTrust } from '../connector/issuerTrustModel'

/**
 * The typed failure causes for the `failed` phase — the four ways the connect PROGRESSION can end negatively before a
 * connection is ever established. A post-connected drop is NOT one of these: it is the distinct `lost` phase (a
 * connection existed and was lost ≠ a connection never formed). Closed union — a new cause must be added here and every
 * `switch` over it re-checked (the tests pin each cause with a mutation tooth).
 */
/**
 * The ISSUER-axis (axis c) failure sub-taxonomy — its OWN extensible union so it composes into ConnectFailureCause
 * additively. PL-endorsed (2026-07-27): 'issuer-not-trusted' (terminal) + 'remote-not-configured' (actionable) NOW; a 3rd
 * 'issuer-revoked' is OPEN (Team-1 input) and must slot in HERE without touching call sites. Every `switch` over
 * ConnectFailureCause is exhaustive (assertNever), so adding a variant fails to COMPILE until it is dispositioned +
 * rendered — forcing re-ratification, never a silent gap. Mirrors the Backend2 CYP-824 sealed `RemoteFailure` issuer arm.
 */
export type IssuerFailureCause = 'issuer-not-trusted' | 'remote-not-configured'

/**
 * The full connect-progression failure taxonomy: the transport/tier causes plus the issuer sub-taxonomy. A post-connected
 * drop is NOT here — it is the distinct `lost` phase (a connection existed and was lost ≠ a connection never formed).
 */
export type ConnectFailureCause = 'connect-refused' | 'handshake-fail' | 'security-tier' | IssuerFailureCause

/**
 * The disposition of a negative outcome — parity with the Backend2 CYP-824 `RemoteFailure` sealed model (reference
 * app/shared/…/RemoteSessionState.kt), so this client taxonomy lines up with the wire shape that lands later (PL/Team-1):
 *   • terminal   = fail-closed, retry cannot help without an out-of-band change (the failure-view offers NO retry).
 *   • retryable  = a transient drop/refusal; re-dialing may succeed.
 *   • actionable = the user can resolve it in-app, then reconnect (NOT a reject — parity with DeviceNotEnrolled).
 * Additive by design: a future issuer sub-variant wire-shape slots in without changing the disposition contract.
 */
export type RemoteFailureDisposition = 'terminal' | 'retryable' | 'actionable'

/**
 * Map a failure cause to its disposition. ANCHORED by Backend2 CYP-824 §4: issuer-not-trusted is terminal/fail-closed
 * (like TrustChanged). ★ PROVISIONAL (flagged to coordinator, pending the CYP-824 wire): connect-refused / handshake-fail
 * are treated as retryable (transient), security-tier as terminal (a policy tier-rejection is not user-fixable in-app).
 * These three are re-ratified when the RemoteFailure wire lands; the disposition SEAM (this function) is where they bind.
 */
export function failureDisposition(cause: ConnectFailureCause): RemoteFailureDisposition {
  switch (cause) {
    case 'issuer-not-trusted':
      return 'terminal' // Backend2 CYP-824 §4 / PL: fail-closed, no retry (IssuerNotTrustedBlock has no retry button).
    case 'remote-not-configured':
      return 'actionable' // PL: NOT a reject — operator establishes issuer trust OOB then reconnects (like DeviceNotEnrolled).
    case 'security-tier':
      return 'terminal' // policy tier-rejection — re-dialing won't change the tier verdict (provisional).
    case 'connect-refused':
      return 'retryable' // transient network refusal (provisional).
    case 'handshake-fail':
      return 'retryable' // transient handshake failure (provisional).
    default:
      return assertNever(cause)
  }
}

/**
 * The connect-progression state. Discriminated on `phase` (mirrors the hubReducers discriminated-union idiom). The happy
 * path is idle → dialing → handshake → trust-check → connected; `failed(cause)` is a terminal negative outcome of the
 * progression; `lost` is a terminal drop of an ESTABLISHED connection. Both terminals are cleared with a `reset` → idle.
 */
export type RemoteConnState =
  | { phase: 'idle' }
  | { phase: 'dialing' }
  | { phase: 'handshake' }
  | { phase: 'trust-check' }
  | { phase: 'connected' }
  | { phase: 'failed'; cause: ConnectFailureCause }
  | { phase: 'lost' }

/** Fail-closed resting state — a fresh machine has NOT dialed. Never optimistic. */
export const initialRemoteConnState: RemoteConnState = { phase: 'idle' }

/**
 * The tier-axis connect verdict, SUPPLIED by the connector/transport seam (CYP-807-A5 / Backend2 CYP-824) — the reducer
 * NEVER computes it from a RemoteSecurityTier value (see the axis-discipline note in the file header: unknown-start would
 * block every web-ts connect). The wire decides; the machine only maps the verdict onto the 'security-tier' cause.
 */
export type TierGate = 'ok' | 'rejected'

/**
 * The sealed ISSUER-axis connect verdict (PL-endorsed 2026-07-27, fail-closed-by-construction): either `proceed`, or
 * `blocked` with a typed issuer cause. The connector/transport (CYP-807-A5) SUPPLIES this — the reducer MAPS it, never
 * re-derives it — so the wire-owned causes ('remote-not-configured' now, a future 'issuer-revoked') enter without the
 * client inventing the policy. Extensible: `cause` is IssuerFailureCause, so a new issuer variant flows through with no
 * shape change here.
 */
export type IssuerConnectVerdict = { outcome: 'proceed' } | { outcome: 'blocked'; cause: IssuerFailureCause }

/**
 * The CANONICAL client derivation of a verdict from a raw HubIssuerTrust — reuses connector/issuerTrustModel's frozen
 * issuerConnectDecision faithfully: ONLY NOT_TRUSTED blocks (→ 'issuer-not-trusted'); TRUSTED / REMOTE_NOT_CONFIGURED /
 * absent all proceed. ★ NOTE (flagged to coordinator): the PL/Backend2 taxonomy also has 'remote-not-configured' as an
 * ACTIONABLE failure, but the frozen issuerConnectDecision PROCEEDS on REMOTE_NOT_CONFIGURED (the server enforces; this
 * render is UX-completeness — CYP-805). So the canonical path never emits 'remote-not-configured' — that verdict comes
 * from the WIRE (A5) directly. This keeps the client 1:1 with the ratified decision and defers the layering
 * reconciliation to the wire rather than guessing it here.
 */
export function issuerVerdictFor(issuer: HubIssuerTrust | null | undefined): IssuerConnectVerdict {
  return issuerConnectDecision(issuer) === 'block' ? { outcome: 'blocked', cause: 'issuer-not-trusted' } : { outcome: 'proceed' }
}

/**
 * The progression events the connector feeds in. Each event names a single observed wire transition; the reducer maps it
 * onto exactly one source→target edge (guarded by phase). `trustEvaluated` is the only event that carries a payload — the
 * two trust-check axes it resolves. The `assertNever` in the reducer makes adding a kind here without a handler a
 * COMPILE error.
 */
export type RemoteConnEvent =
  | { kind: 'dial' } // idle → dialing
  | { kind: 'dialRefused' } // dialing → failed('connect-refused')
  | { kind: 'handshakeOpen' } // dialing → handshake
  | { kind: 'handshakeFailed' } // handshake → failed('handshake-fail')
  | { kind: 'handshakeOk' } // handshake → trust-check
  | { kind: 'trustEvaluated'; verdict: IssuerConnectVerdict; tierGate: TierGate } // trust-check → connected | failed(issuer|tier)
  | { kind: 'dropped' } // connected → lost
  | { kind: 'reset' } // any (non-idle) → idle

/**
 * Resolve the trust-check step from the connector-supplied verdict + tier gate. TWO independent axes, evaluated in a fixed
 * order — ISSUER first (axis c), then TIER — so the surfaced cause is deterministic when both would block. (Precedence is
 * a deliberate choice, flagged to PL: issuer before tier because the issuer refusal is the hub declining the operator
 * vouch outright, a stronger "stop" than the advisory tier register.) The verdict is the sealed IssuerConnectVerdict — a
 * `blocked` verdict carries its own typed issuer cause (fail-closed-by-construction); the reducer never re-derives it.
 */
function resolveTrustCheck(verdict: IssuerConnectVerdict, tierGate: TierGate): RemoteConnState {
  if (verdict.outcome === 'blocked') return { phase: 'failed', cause: verdict.cause }
  if (tierGate === 'rejected') return { phase: 'failed', cause: 'security-tier' }
  return { phase: 'connected' }
}

/**
 * The pure connect-progression transition: (state, event) → state. Every edge is GUARDED by the current phase — an event
 * that does not apply to the current phase is a no-op that returns the SAME state reference (the codebase's no-op-stable
 * idiom; asserted with `.toBe` teeth). This makes illegal jumps (e.g. idle → connected) structurally impossible: there
 * is no unguarded edge. `reset` is the one any-phase edge (dismiss a terminal / tear down) and still no-ops when already
 * idle.
 */
export function remoteConnReduce(state: RemoteConnState, event: RemoteConnEvent): RemoteConnState {
  switch (event.kind) {
    case 'dial':
      return state.phase === 'idle' ? { phase: 'dialing' } : state
    case 'dialRefused':
      return state.phase === 'dialing' ? { phase: 'failed', cause: 'connect-refused' } : state
    case 'handshakeOpen':
      return state.phase === 'dialing' ? { phase: 'handshake' } : state
    case 'handshakeFailed':
      return state.phase === 'handshake' ? { phase: 'failed', cause: 'handshake-fail' } : state
    case 'handshakeOk':
      return state.phase === 'handshake' ? { phase: 'trust-check' } : state
    case 'trustEvaluated':
      return state.phase === 'trust-check' ? resolveTrustCheck(event.verdict, event.tierGate) : state
    case 'dropped':
      // ONLY a connected connection can be 'lost'. A drop during dialing/handshake surfaces as its own progression
      // failure (dialRefused/handshakeFailed), not as 'lost' — so from any non-connected phase this is a no-op.
      return state.phase === 'connected' ? { phase: 'lost' } : state
    case 'reset':
      return state.phase === 'idle' ? state : { phase: 'idle' }
    default:
      // Exhaustiveness guard: adding a RemoteConnEvent kind without a case above fails to COMPILE here (a
      // `default: return state` would swallow the new kind silently). Mirrors hubReducers.applyCommEvent.
      return assertNever(event)
  }
}

/** Compile-time proof that every event kind is handled; unreachable at runtime by construction. */
function assertNever(x: never): never {
  throw new Error(`unhandled RemoteConnEvent kind: ${JSON.stringify(x)}`)
}

/** The stateful machine runtime: holds the current RemoteConnState, folds fed events through the pure reducer, and
 *  notifies subscribers only when the state reference actually changes (no-op events do not fire). A connector (the fake
 *  in tests, the real transport in A5) drives it by calling `send`; a view (A2/A3) reads via `getState`/`subscribe`. */
export interface RemoteConnMachine {
  getState(): RemoteConnState
  send(event: RemoteConnEvent): void
  subscribe(listener: (state: RemoteConnState) => void): () => void
}

export function createRemoteConnMachine(initial: RemoteConnState = initialRemoteConnState): RemoteConnMachine {
  let state = initial
  const listeners = new Set<(state: RemoteConnState) => void>()
  return {
    getState: () => state,
    send: (event) => {
      const next = remoteConnReduce(state, event)
      if (next === state) return // no-op edge: reference unchanged → no notify
      state = next
      for (const l of listeners) l(state)
    },
    subscribe: (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
  }
}
