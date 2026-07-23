// CYP-400 (W2) — a generic server->client feed over the reconnecting socket. The four read-only status channels
// (/ws/lifecycle, /ws/token-usage, /ws/busy-state, /ws/terminal-state) are all "connect, receive typed events,
// auto-reconnect" — this is that shape, type-parameterised by the channel's event type (from W1's generated
// contract). Concrete per-channel instances are one-liners once each channel's type is generated (fixture
// expansion or Backend2's real export). `?token=` auth; per-agent channels pass `query: { agentId }`.
import { deliverIfValid, rejectUnvalidated } from './wsValidation'
import { ReconnectingSocket, type SocketFactory, type Scheduler } from './reconnectingSocket'
import { Backoff } from './backoff'

export interface OneWayFeedOptions<T> {
  baseUrl: string
  path: string
  token: string
  onEvent: (event: T) => void
  /** CYP-420: untrusted-frame boundary. Throws FrameValidationError to reject; the frame is then DROPPED. This
   *  channel previously cast raw JSON straight to T — no runtime check at all. */
  validate?: (raw: unknown) => T
  query?: Record<string, string>
  onOpen?: () => void
  /** CYP-815: an UNEXPECTED close (1008 = auth revoked) — the view flips to an offline/revoked signal (CYP-437),
   *  parity with BidiFeed (comm/events). Previously ABSENT here: an onClose passed via the `...o` spread (ChannelBase
   *  declares it) was silently dropped, so the 4 read-only status feeds died silent on 1008 (safe-but-silent). */
  onClose?: (code?: number) => void
  backoff?: Backoff
  factory?: SocketFactory
  schedule?: Scheduler
}

export class OneWayFeed<T> {
  private readonly rs: ReconnectingSocket

  constructor(opts: OneWayFeedOptions<T>) {
    // CYP-420 (Assist2 F1): fail-CLOSED default — a forgotten validator drops+reports, never silently passes.
    const validate = opts.validate ?? rejectUnvalidated<T>(opts.path)
    this.rs = new ReconnectingSocket({
      url: () => {
        const p = new URLSearchParams({ ...(opts.query ?? {}), token: opts.token })
        return `${opts.baseUrl}${opts.path}?${p.toString()}`
      },
      // CYP-420: runtime-validated; an invalid frame is dropped, never delivered (was: an unchecked cast).
      onText: (data) => deliverIfValid(validate, data, opts.onEvent),
      onOpen: opts.onOpen,
      onClose: opts.onClose, // CYP-815: forward the close (was dropped) → status feeds surface offline/revoked on 1008.
      backoff: opts.backoff,
      factory: opts.factory,
      schedule: opts.schedule,
    })
  }

  start(): void {
    this.rs.connect()
  }

  close(): void {
    this.rs.close()
  }
}
