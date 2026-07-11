// CYP-403 (W5) — the one global, DURABLE composer-history size N (spec §3): default 20, clamp 0..200 (0 = off).
// Web parity of the Kotlin keyed-KV pref (localStorage here). The store is injected so it is unit-testable and
// non-DOM-safe; `browserStore()` supplies localStorage in the app.
import { DEFAULT_HISTORY_SIZE, MAX_HISTORY_SIZE } from './inputHistory'

const KEY = 'cyppie.composer.historySize'

export interface KvStore {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
}

/** Clamp to [0, MAX], truncating to an int; non-finite falls back to the default. */
export function clampHistorySize(n: number): number {
  if (!Number.isFinite(n)) return DEFAULT_HISTORY_SIZE
  return Math.max(0, Math.min(MAX_HISTORY_SIZE, Math.trunc(n)))
}

export function loadHistorySize(store: KvStore): number {
  const raw = store.getItem(KEY)
  if (raw === null) return DEFAULT_HISTORY_SIZE
  const n = Number(raw)
  return Number.isFinite(n) ? clampHistorySize(n) : DEFAULT_HISTORY_SIZE
}

export function saveHistorySize(store: KvStore, n: number): number {
  const clamped = clampHistorySize(n)
  store.setItem(KEY, String(clamped))
  return clamped
}

/** The app's durable store. Throws in a non-DOM env — tests inject their own KvStore. */
export function browserStore(): KvStore {
  return localStorage
}
