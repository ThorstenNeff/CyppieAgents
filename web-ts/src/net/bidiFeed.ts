// CYP-400 (W2-rest) — a generic bidirectional channel over the reconnecting socket: typed server->client events
// plus a typed client->server send. Used by /ws/comm and /ws/events (both send a Subscribe) and the /ws/terminal
// transport. Unlike agentSocket there is no seq cursor: these channels resend a snapshot on (re)connect and the
// consumer upserts by id (idempotent at the view layer), so the transport only delivers + reconnects.
//
// `validate` is the untrusted-frame boundary seam (Spec 14 §7 / Assist hardening): a hook to runtime-check each
// inbound frame (e.g. a generated zod schema). Default is identity; wiring zod uniformly is the W2-rest follow-up.
import { deliverIfValid, rejectUnvalidated, type FrameRejection } from './wsValidation'
import { ReconnectingSocket, type SocketFactory, type Scheduler } from './reconnectingSocket'
import { wsAuthParams } from './wsTicket'
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
  /** CYP-834 (OPTIONAL, additive): a channel-scoped hook for a SCHEMA-violation frame. When wired, a schema violation is
   *  TERMINAL for this channel — the socket is closed (NO reconnect: a decode/deploy skew replays forever otherwise) and
   *  `onReject` fires so the view can surface a distinct terminal state. When ABSENT, behavior is unchanged (the global
   *  drop+warn, channel lives on) — so existing callers are unaffected. */
  onReject?: (rejection: FrameRejection) => void
  backoff?: Backoff
  factory?: SocketFactory
  schedule?: Scheduler
  /** CYP-881 (DARK): when present (flag ON), the socket mints a FRESH single-use ticket per (re)connect and folds
   *  `?ticket=` instead of `?token=`. ABSENT (flag OFF / default) → byte-unchanged `?token=` path. Only the READ feeds
   *  (comm/events) opt in; the terminal egress caller passes none, so it stays `?token=`. Injectable for tests. */
  ticketProvider?: () => Promise<string>
}

export class BidiFeed<TServer, TClient> {
  private readonly rs: ReconnectingSocket

  constructor(opts: BidiFeedOptions<TServer>) {
    // CYP-420 (Assist2 F1): fail-CLOSED default — a forgotten validator drops+reports, never silently passes.
    const validate = opts.validate ?? rejectUnvalidated<TServer>(opts.path)
    // CYP-834: if the caller opts into channel-scoped skew handling, a schema violation is TERMINAL — fire onReject AND
    // close this socket so it never reconnects (a decode/deploy skew would replay the same undecodable frame forever
    // under a generic "offline"). Absent → the default global drop path (backward-compatible, channel lives on).
    const onReject = opts.onReject
      ? (rejection: FrameRejection): void => {
          opts.onReject!(rejection)
          this.rs.close()
        }
      : undefined
    this.rs = new ReconnectingSocket({
      // CYP-881 (DARK): `ticket` present (flag ON) → fold `?ticket=`; absent → `?token=`. CYP-902: a blank token folds
      // to no auth param → omit the `?` entirely (a clean cookie-only handshake, not a bare trailing `?`).
      url: (ticket) => {
        const qs = wsAuthParams(opts.query, opts.token, ticket)
        return `${opts.baseUrl}${opts.path}${qs === '' ? '' : `?${qs}`}`
      },
      ticketProvider: opts.ticketProvider,
      // CYP-420: validation failures DROP the frame (deliverIfValid) instead of throwing into the socket's
      // onmessage — one malformed frame must not tear down a live channel (fail-closed, not fail-brittle).
      // CYP-834: a wired onReject re-routes a SCHEMA violation to the terminal-skew path above.
      onText: (data) => deliverIfValid(validate, data, opts.onEvent, onReject),
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
