// CYP-403 (W5) — TS port of :app:shared's InputHistory (CYP-387). A bounded, in-memory ring of the messages the
// operator has SENT to one agent, newest last. Session-scoped (per composer), no persistence in v1. THE STORE
// ONLY — the arrow-key cursor is composerRecall.ts. Capacity is a LIVE supplier (`() => number`) so a change to
// the one global N takes effect on an open composer without rebuilding (which would wipe the content). Spec:
//  - N <= 0 → history OFF: record is a no-op, entries empty.
//  - at N the OLDEST is evicted (HISTSIZE model).
//  - NO dedup in v1 (consecutive duplicates both kept).
//  - blank text is never recorded.
export const DEFAULT_HISTORY_SIZE = 20
export const MAX_HISTORY_SIZE = 200

export class InputHistory {
  private readonly capacity: () => number
  private readonly buffer: string[] = [] // newest at the end

  /** Pass a live supplier for the global N, or a fixed number. */
  constructor(capacity: (() => number) | number) {
    this.capacity = typeof capacity === 'number' ? () => capacity : capacity
  }

  /** Newest-last snapshot, bounded to the CURRENT capacity (so a shrink of N shows on read before the next send). */
  get entries(): string[] {
    const cap = Math.max(0, this.capacity())
    return cap === 0 ? [] : this.buffer.slice(-cap)
  }

  get size(): number {
    return this.entries.length
  }

  /** Record a sent message. No-op when N <= 0 or the text is blank; evicts the oldest at N. */
  record(text: string): void {
    const cap = this.capacity()
    if (cap <= 0) return
    const trimmed = text.trim()
    if (trimmed === '') return
    this.buffer.push(trimmed)
    while (this.buffer.length > cap) this.buffer.shift()
  }
}
