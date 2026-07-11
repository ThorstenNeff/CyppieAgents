// CYP-400 (W2) — deterministic exponential backoff for socket reconnects. No randomness, so reconnect timing is
// unit-testable (port of the server-side Backoff idea, CYP-204).
export interface BackoffOptions {
  initialMs?: number
  maxMs?: number
  factor?: number
}

export class Backoff {
  private readonly initialMs: number
  private readonly maxMs: number
  private readonly factor: number
  private current: number

  constructor(opts: BackoffOptions = {}) {
    this.initialMs = opts.initialMs ?? 500
    this.maxMs = opts.maxMs ?? 10_000
    this.factor = opts.factor ?? 2
    this.current = this.initialMs
  }

  /** The next delay (ms), then grow towards [maxMs]. */
  next(): number {
    const delay = this.current
    this.current = Math.min(this.maxMs, this.current * this.factor)
    return delay
  }

  /** Back to the initial delay — call on a successful (re)connect. */
  reset(): void {
    this.current = this.initialMs
  }
}
