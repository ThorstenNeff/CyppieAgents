// CYP-400 (W2) — the channel-agnostic reconnecting WebSocket that ALL 8 frontend WS channels share. It owns the
// hard parts: `?token=` auth (via the caller's url()), auto-reconnect with backoff, and clean teardown. Per-channel
// concerns (the `?since=` cursor, id/seq idempotency, typed parsing) live in the channel wrappers on top of this.
//
// Browser WebSocket cannot set request headers, so auth rides the query (`?token=`), proxy-masked (CYP-292, §6).
// The socket factory and scheduler are injectable so reconnect/dedup are deterministically unit-testable without a
// real socket or real timers.
import { Backoff } from './backoff'
import { isLegacyReconnectableClose } from './closeVerdict'

/** The slice of the WHATWG WebSocket we use — lets tests inject a fake without a DOM. */
export interface SocketLike {
  send(data: string): void
  close(): void
  onopen: ((ev: unknown) => void) | null
  onmessage: ((ev: { data: unknown }) => void) | null
  onclose: ((ev: unknown) => void) | null
  onerror: ((ev: unknown) => void) | null
}

export type SocketFactory = (url: string) => SocketLike
export type Scheduler = (fn: () => void, ms: number) => void

export interface ReconnectingSocketOptions {
  /** Computed on EVERY (re)connect, so a channel can fold its live cursor (e.g. `?since=<lastSeq>`) into the URL. */
  url: () => string
  /** One inbound text frame. */
  onText: (data: string) => void
  onOpen?: () => void
  /** An UNEXPECTED close (not a deliberate close()) — the view layer flips to an offline/revoked banner (CYP-437).
   *  `code` is the WebSocket close code when the transport supplies one (1008 = policy violation = auth revoked). */
  onClose?: (code?: number) => void
  backoff?: Backoff
  factory?: SocketFactory
  schedule?: Scheduler
}

const defaultFactory: SocketFactory = (url) => new WebSocket(url) as unknown as SocketLike
const defaultSchedule: Scheduler = (fn, ms) => {
  setTimeout(fn, ms)
}

export class ReconnectingSocket {
  private sock: SocketLike | null = null
  private open = false
  private closed = false
  private readonly backoff: Backoff
  private readonly factory: SocketFactory
  private readonly schedule: Scheduler

  constructor(private readonly opts: ReconnectingSocketOptions) {
    this.backoff = opts.backoff ?? new Backoff()
    this.factory = opts.factory ?? defaultFactory
    this.schedule = opts.schedule ?? defaultSchedule
  }

  connect(): void {
    if (this.closed) return
    const sock = this.factory(this.opts.url())
    this.sock = sock
    sock.onopen = () => {
      this.open = true
      this.backoff.reset()
      this.opts.onOpen?.()
    }
    sock.onmessage = (ev) => {
      if (typeof ev.data === 'string') this.opts.onText(ev.data)
    }
    sock.onclose = (ev) => {
      this.open = false
      this.sock = null
      if (!this.closed) {
        const code = (ev as { code?: number } | undefined)?.code
        // 1008 = policy violation = auth revoked → TERMINAL: reconnecting with a dead token is a useless (and, for
        // an egress channel, unsafe) loop. Mark closed so we never reconnect; the view fails closed (CYP-432).
        // CYP-839: the 1008-terminal verdict is single-sourced (local-hub deny-{1008} policy, isLegacyReconnectableClose).
        if (!isLegacyReconnectableClose(code)) this.closed = true
        // unexpected drop → tell the view (offline/revoked banner); reconnect only if not terminal
        this.opts.onClose?.(code)
        if (!this.closed) this.schedule(() => this.connect(), this.backoff.next())
      }
    }
    sock.onerror = () => {
      // Let onclose drive reconnect; some implementations fire error then close.
    }
  }

  /** Send a text frame if connected. Returns false if dropped (offline) — the caller decides whether to retry. */
  send(data: string): boolean {
    if (!this.open || this.sock === null) return false
    this.sock.send(data)
    return true
  }

  /** Permanent close — no further reconnects. */
  close(): void {
    this.closed = true
    this.open = false
    this.sock?.close()
    this.sock = null
  }
}
