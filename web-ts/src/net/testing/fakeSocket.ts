// CYP-400 (W2) — test double for the injected SocketLike, so reconnect/idempotency are driven deterministically
// (no real WebSocket, no real timers). Not a *.test.ts, so vitest never runs it as a suite.
import type { SocketFactory, SocketLike, Scheduler } from '../reconnectingSocket'

export class FakeSocket implements SocketLike {
  onopen: ((ev: unknown) => void) | null = null
  onmessage: ((ev: { data: unknown }) => void) | null = null
  onclose: ((ev: unknown) => void) | null = null
  onerror: ((ev: unknown) => void) | null = null
  readonly sent: string[] = []
  closed = false

  constructor(readonly url: string) {}

  send(data: string): void {
    this.sent.push(data)
  }

  close(): void {
    this.closed = true
    this.onclose?.(undefined)
  }

  // --- test drivers ---
  emitOpen(): void {
    this.onopen?.(undefined)
  }
  emitMessage(data: string): void {
    this.onmessage?.({ data })
  }
  emitClose(): void {
    this.onclose?.(undefined)
  }
}

/** Records every socket the factory hands out; the scheduler runs reconnects synchronously for deterministic tests. */
export class FakeSocketHub {
  readonly sockets: FakeSocket[] = []

  readonly factory: SocketFactory = (url) => {
    const s = new FakeSocket(url)
    this.sockets.push(s)
    return s
  }

  readonly runNow: Scheduler = (fn) => {
    fn()
  }

  last(): FakeSocket {
    const s = this.sockets.at(-1)
    if (s === undefined) throw new Error('no socket created yet')
    return s
  }
}
