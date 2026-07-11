// CYP-400 (W2) — a generic server->client feed over the reconnecting socket. The four read-only status channels
// (/ws/lifecycle, /ws/token-usage, /ws/busy-state, /ws/terminal-state) are all "connect, receive typed events,
// auto-reconnect" — this is that shape, type-parameterised by the channel's event type (from W1's generated
// contract). Concrete per-channel instances are one-liners once each channel's type is generated (fixture
// expansion or Backend2's real export). `?token=` auth; per-agent channels pass `query: { agentId }`.
import { ReconnectingSocket, type SocketFactory, type Scheduler } from './reconnectingSocket'
import { Backoff } from './backoff'

export interface OneWayFeedOptions<T> {
  baseUrl: string
  path: string
  token: string
  onEvent: (event: T) => void
  query?: Record<string, string>
  onOpen?: () => void
  backoff?: Backoff
  factory?: SocketFactory
  schedule?: Scheduler
}

export class OneWayFeed<T> {
  private readonly rs: ReconnectingSocket

  constructor(opts: OneWayFeedOptions<T>) {
    this.rs = new ReconnectingSocket({
      url: () => {
        const p = new URLSearchParams({ ...(opts.query ?? {}), token: opts.token })
        return `${opts.baseUrl}${opts.path}?${p.toString()}`
      },
      onText: (data) => opts.onEvent(JSON.parse(data) as T),
      onOpen: opts.onOpen,
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
