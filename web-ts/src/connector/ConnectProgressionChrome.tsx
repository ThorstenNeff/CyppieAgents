// CYP-855 (CYP-807-A Multi-Hub M5) — the connect-progression chrome: the Zone-2 in-flight states of the ACTIVE hub's
// connect (CYP-827 UX spec, copy/tags verbatim from Compose `remote_connect_*` / `RemoteConnectTags`). ONE connection
// at a time (switch-first) → not per-hubId. It resolves to CONNECTED (positive) or the Failure-Region (failed(cause),
// CYP-823) — it is NEVER always-visible Zone-1 status chrome (a transient step must not persist as durable status).
//
// ★ authenticating (CYP-827 6th state, copy "Operator wird bestätigt …") is intentionally COMPOSE-ONLY, not a web
// render (Backend2 ruling, protocol owner: a legit cross-platform fold, not a gap). Native separates TWO proofs over
// the noise tunnel — hub identity (`trust-check`, CI-1) AND operator authority (a distinct `AUTHENTICATING` phase =
// WebAuthn PoP, CYP-482/CYP-542). web relocates operator authority into the TRANSPORT handshake: the same-origin
// httpOnly session cookie authenticates the WSS handshake (CYP-454), so the operator is already authenticated at
// socket-open — no distinct post-trust-check step. Not a dropped auth step, just relocated. (Likewise native's
// AUTHENTICATING failure taxonomy is native-only; a web auth failure surfaces at the handshake as connect-refused /
// 1008 → Kratos re-login, not a progression phase — so it needs no web home either.) web-ts M5 = these five states.
//
// Honesty core (CYP-827 §2):
//  • trust-check is PROVISIONAL — an always-visible "vorläufig, echtes Pinnen folgt" disclosure; it must NEVER read as
//    "verified/trusted" before real pinning (that verdict is axis-a HubTrustState, Zone-1, separate).
//  • reconnecting is honestly-uncertain + retryable — neutral, NEVER alarm-red, NEVER routed to the terminal failure region.
//  • CONNECTED shows the `●` LIVE marker (primary) — reached ONLY at real connected, never optimistically before it; and
//    this liveness idiom is NOT the axis-a trust affirmation (which stays neutral — two axes, never conflated).
//  • a11y: every progression step is role=status / aria-live=polite (calm). Assertive stays reserved for terminal
//    failure / active-hub-switch / revoke (CYP-825) — a progression that shouted every step would drown the real terminal.
import type { RemoteConnState } from '../state/remoteConnState'

/** The in-flight progression states the chrome renders (a subset/relabel of the machine's phases; failed/lost are the
 *  Failure-Region, idle is not-connecting). `authenticating` is deferred — see the file header. */
export type ProgressionState = 'dialing' | 'handshake' | 'trust-check' | 'reconnecting' | 'connected'

function assertNever(x: never): never {
  throw new Error(`unhandled RemoteConnState phase: ${JSON.stringify(x)}`)
}

/**
 * Map the active RemoteConnState to the progression state to render, or null when the connect is NOT in an in-flight
 * progression: `idle` (not connecting) and the terminal `failed`/`lost` (the Failure-Region CYP-823 owns those, never
 * the progression chrome). Exhaustive over the frozen phase union (assertNever).
 */
export function progressionStateFor(conn: RemoteConnState): ProgressionState | null {
  switch (conn.phase) {
    case 'dialing':
      return 'dialing'
    case 'handshake':
      return 'handshake'
    case 'trust-check':
      return 'trust-check'
    case 'reconnecting':
      return 'reconnecting'
    case 'connected':
      return 'connected'
    case 'idle':
    case 'failed':
    case 'lost':
      return null // not an in-flight progression step (failed/lost → Failure-Region CYP-823)
    default:
      return assertNever(conn)
  }
}

/** Copy + Compose-parity tag per state (verbatim, CYP-827 §1 / `remote_connect_*`). */
const PROGRESSION_COPY: Record<ProgressionState, { text: string; tag: string }> = {
  dialing: { text: 'Relay wird gewählt …', tag: 'remote.connect.relayDialing' },
  handshake: { text: 'E2E-Handshake …', tag: 'remote.connect.e2eHandshake' },
  'trust-check': { text: 'Hub-Vertrauen wird geprüft …', tag: 'remote.connect.trustCheck' },
  reconnecting: { text: 'Verbindung unterbrochen — verbinde neu …', tag: 'remote.relayDrop' },
  connected: { text: 'Verbunden', tag: 'remote.connect.connected' },
}
/** trust-check provisional disclosure (CYP-475 §-QA①, verbatim) — always-visible, never tap-to-reveal. */
const TRUST_PROVISIONAL = 'Vertrauensprüfung vorläufig — echtes Pinnen folgt.'
/** CONNECTED forward action (no dead-end, Compose CYP-523 parity). */
const CONNECTED_ACTION = 'Loslegen'

export function ConnectProgressionChrome({ state, onEnterWorkspace }: { state: ProgressionState; onEnterWorkspace?: () => void }) {
  const copy = PROGRESSION_COPY[state]
  const connected = state === 'connected'
  return (
    // Zone-2 in-flight card. role=status / aria-live=polite for EVERY progression step (CYP-827 §3 — never assertive).
    <div
      className={`remote-progression remote-progression-${state}`}
      data-testid={`remote.progression.${state}`}
      data-tag={copy.tag}
      role="status"
      aria-live="polite"
    >
      {connected ? (
        // `●` LIVE idiom (primary) — ONLY at real CONNECTED, never optimistically. Liveness, NOT axis-a trust (neutral).
        <span className="remote-progression-live" data-testid="remote.progression.live" aria-hidden="true">
          ●
        </span>
      ) : (
        // neutral spinner — colour never the sole carrier (the copy word carries the meaning). No live marker pre-connected.
        <span className="remote-progression-spinner" aria-hidden="true" />
      )}
      <span className="remote-progression-text">{copy.text}</span>
      {state === 'trust-check' && (
        // PROVISIONAL — in-flight, never "verified". Neutral labelSmall, no alarm.
        <span
          className="remote-progression-provisional"
          data-testid="remote.connect.trustProvisional"
          data-tag="remote.connect.trustProvisional"
        >
          {TRUST_PROVISIONAL}
        </span>
      )}
      {connected && onEnterWorkspace && (
        <button type="button" className="remote-progression-enter" data-testid="remote.connect.toWorkspace" onClick={onEnterWorkspace}>
          {CONNECTED_ACTION}
        </button>
      )}
    </div>
  )
}
