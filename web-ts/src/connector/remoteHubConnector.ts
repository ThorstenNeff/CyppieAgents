// CYP-807-A5 (Real-Socket-Consume, wiring structure) — the RemoteHubConnector: drives a CYP-826 RemoteConnState machine
// from a WebSocket lifecycle for a REMOTE hub. Built against the injectable SocketLike/Scheduler seam so the whole
// progression is unit-testable with a fake socket — NO real remote is opened here.
//
// ★ LIVE-ARMING IS GATED (PL): the off-loopback flip waits on Team-1 security fixes (CYP-828 god-token + getenv-lint,
// §9.3 cookie-jar) + CYP-747 deploy activation. This module is the STRUCTURE only; nothing calls it with a real remote
// factory until PL's explicit arming-GO. The default factory (real WebSocket) exists for that future, but today every
// caller/test injects a fake.
//
// ★ TRUST-CHECK is CLIENT-SIDE / NO-ORACLE (CYP-831, ratified): the issuer verdict is computed from the HubDescriptor the
// client already holds (computeIssuerPreVerdict), applied at the trust-check step. The client DIALS and applies the
// ADVISORY verdict; it never self-gates the transport — the SERVER connect-gate is the fail-closed enforcement.
//
// ★ CLOSE HANDLING is FAIL-CLOSED (CYP-833): a drop of an established connection maps to dropped{terminal} via
// isTerminalClose — only an explicitly known-reconnectable code reconnects; 1008 and every unknown code are terminal.
import type { HubDescriptor } from '../types/generated/contract'
import type { SocketFactory, SocketLike, Scheduler } from '../net/reconnectingSocket'
import { Backoff } from '../net/backoff'
import { isTerminalClose } from '../net/closeVerdict'
import { computeIssuerPreVerdict } from './issuerPreVerdict'
import { createRemoteConnMachine, type RemoteConnMachine, type TierGate } from '../state/remoteConnState'

export interface RemoteHubConnectorDeps {
  /** Injectable socket factory — tests pass a fake; the real WebSocket default is only for the (gated) live path. */
  factory?: SocketFactory
  /** Injectable reconnect scheduler — tests run it synchronously. */
  schedule?: Scheduler
  backoff?: Backoff
  /** The tier-axis connect verdict (the connector/Backend transport seam). Stub default 'ok'; real tier at live-arming. */
  tierGate?: TierGate
}

export interface RemoteHubConnection {
  /** The connect-progression machine — a view (Failure-View-Host) subscribes to render the honest arm. */
  readonly machine: RemoteConnMachine
  /** Deliberate teardown — no failure mapping, no further reconnect. */
  close(): void
}

const defaultFactory: SocketFactory = (url) => new WebSocket(url) as unknown as SocketLike
const defaultSchedule: Scheduler = (fn, ms) => {
  setTimeout(fn, ms)
}

/**
 * Open a remote-hub connect and drive its RemoteConnState machine. Progression (stub handshake mapping — real hub
 * app-hello lands at live-arming): dial → (socket open) handshake → handshakeOk → trust-check(computeIssuerPreVerdict) →
 * connected | failed(cause). A drop of the established connection → dropped{terminal} (fail-closed) → lost (terminal) or
 * reconnecting + a scheduled re-dial (known-reconnectable). A drop before it opens → connect-refused.
 */
export function connectRemoteHub(
  descriptor: HubDescriptor,
  wsUrl: string,
  deps: RemoteHubConnectorDeps = {},
): RemoteHubConnection {
  const factory = deps.factory ?? defaultFactory
  const schedule = deps.schedule ?? defaultSchedule
  const backoff = deps.backoff ?? new Backoff()
  const tierGate: TierGate = deps.tierGate ?? 'ok'
  const machine = createRemoteConnMachine()
  let closed = false
  let sock: SocketLike | null = null

  const dial = (): void => {
    if (closed) return
    machine.send({ kind: 'dial' }) // idle | reconnecting → dialing
    const s = factory(wsUrl)
    sock = s
    s.onopen = () => {
      // Stub handshake mapping: socket-open ⇒ transport handshake complete (real app-hello slots in here at live-arming).
      machine.send({ kind: 'handshakeOpen' }) // dialing → handshake
      machine.send({ kind: 'handshakeOk' }) // handshake → trust-check
      // Client-side advisory verdict (no oracle) + the (stub) tier gate → connected | failed(cause).
      machine.send({ kind: 'trustEvaluated', verdict: computeIssuerPreVerdict(descriptor), tierGate })
      backoff.reset()
      // A blocked advisory ends the attempt — close the socket deliberately (no drop mapping, no reconnect).
      if (machine.getState().phase === 'failed') {
        closed = true
        s.close()
        sock = null
      }
    }
    s.onclose = (ev) => {
      sock = null
      if (closed) return // a deliberate close (teardown or blocked-verdict) — not a drop
      const code = (ev as { code?: number } | undefined)?.code
      const phase = machine.getState().phase
      if (phase === 'dialing') {
        machine.send({ kind: 'dialRefused' }) // closed before it ever opened → connect-refused
      } else if (phase === 'handshake') {
        machine.send({ kind: 'handshakeFailed' }) // closed mid-handshake (real async path)
      } else if (phase === 'connected' || phase === 'reconnecting') {
        // ★ CYP-833 fail-closed: only a known-reconnectable code reconnects; 1008 + every unknown code are terminal.
        const terminal = isTerminalClose(code)
        machine.send({ kind: 'dropped', terminal })
        if (!terminal && !closed) schedule(() => dial(), backoff.next())
      }
      // else (idle / already failed / lost) → ignore
    }
    s.onerror = () => {
      // Let onclose drive the outcome (some socket impls fire error then close).
    }
    s.onmessage = () => {
      // A5 structure: inbound-frame handling (app-hello, server tier resolution) lands at live-arming; the stub drives
      // the lifecycle (open/close) only.
    }
  }

  dial()

  return {
    machine,
    close: () => {
      closed = true
      sock?.close()
      sock = null
    },
  }
}
