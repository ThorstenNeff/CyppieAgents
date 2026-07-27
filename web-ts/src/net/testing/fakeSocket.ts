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
  emitClose(code?: number): void {
    this.onclose?.(code === undefined ? undefined : { code })
  }
  // CYP-814 G2: drive an error (some WS impls fire error then close). Lets tests exercise the onerror path.
  emitError(): void {
    this.onerror?.(undefined)
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

  // CYP-814 G2: the reconnect-DELAY recorder. `runNow` discards the `ms` arg, so the backoff→scheduler wiring
  // (delay = backoff.next(), reset-on-success) was structurally untestable. This records each scheduled delay, then
  // runs the reconnect synchronously (deterministic) — so a test can assert the delays came from the backoff.
  readonly delays: number[] = []
  readonly recordingSchedule: Scheduler = (fn, ms) => {
    this.delays.push(ms)
    fn()
  }

  last(): FakeSocket {
    const s = this.sockets.at(-1)
    if (s === undefined) throw new Error('no socket created yet')
    return s
  }
}
