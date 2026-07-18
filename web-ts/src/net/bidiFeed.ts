// CYP-400 (W2-rest) — a generic bidirectional channel over the reconnecting socket: typed server->client events
// plus a typed client->server send. Used by /ws/comm and /ws/events (both send a Subscribe) and the /ws/terminal
// transport. Unlike agentSocket there is no seq cursor: these channels resend a snapshot on (re)connect and the
// consumer upserts by id (idempotent at the view layer), so the transport only delivers + reconnects.
//
// `validate` is the untrusted-frame boundary seam (Spec 14 §7 / Assist hardening): a hook to runtime-check each
// inbound frame (e.g. a generated zod schema). Default is identity; wiring zod uniformly is the W2-rest follow-up.
import { deliverIfValid, rejectUnvalidated } from './wsValidation'
import { ReconnectingSocket, type SocketFactory, type Scheduler } from './reconnectingSocket'
import { Backoff } from './backoff'

export interface BidiFeedOptions<TServer> {
  baseUrl: string
  path: string
  token: string
  onEvent: (event: TServer) => void
  query?: Record<string, string>
  /** Untrusted-frame boundary: validate/parse the raw inbound object. Throws to reject; returns the typed frame. */
  validate?: (raw: unknown) => TServer
  onOpen?: () => void
  onClose?: (code?: number) => void
  backoff?: Backoff
  factory?: SocketFactory
  schedule?: Scheduler
}

export class BidiFeed<TServer, TClient> {
  private readonly rs: ReconnectingSocket

  constructor(opts: BidiFeedOptions<TServer>) {
    // CYP-420 (Assist2 F1): fail-CLOSED default — a forgotten validator drops+reports, never silently passes.
    const validate = opts.validate ?? rejectUnvalidated<TServer>(opts.path)
    this.rs = new ReconnectingSocket({
      url: () => {
        const p = new URLSearchParams({ ...(opts.query ?? {}), token: opts.token })
        return `${opts.baseUrl}${opts.path}?${p.toString()}`
      },
      // CYP-420: validation failures DROP the frame (deliverIfValid) instead of throwing into the socket's
      // onmessage — one malformed frame must not tear down a live channel (fail-closed, not fail-brittle).
      onText: (data) => deliverIfValid(validate, data, opts.onEvent),
      onOpen: opts.onOpen,
      onClose: opts.onClose,
      backoff: opts.backoff,
      factory: opts.factory,
      schedule: opts.schedule,
    })
  }

  start(): void {
    this.rs.connect()
  }

  /** Send a typed client->server frame (e.g. Subscribe, a terminal Input/Resize). Dropped (false) while offline. */
  send(frame: TClient): boolean {
    return this.rs.send(JSON.stringify(frame))
  }

  close(): void {
    this.rs.close()
  }
}
