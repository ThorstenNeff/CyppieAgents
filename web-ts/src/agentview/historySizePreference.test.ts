import { describe, it, expect } from 'vitest'
import { loadHistorySize, saveHistorySize, clampHistorySize, type KvStore } from './historySizePreference'

function fakeStore(init: Record<string, string> = {}): KvStore {
  const m = new Map(Object.entries(init))
  return { getItem: (k) => m.get(k) ?? null, setItem: (k, v) => void m.set(k, v) }
}

const KEY = 'cyppie.composer.historySize'

describe('historySizePreference (durable N, CYP-403)', () => {
  it('defaults to 20 when unset', () => {
    expect(loadHistorySize(fakeStore())).toBe(20)
  })

  it('clamps to [0, 200], truncating to an int', () => {
    expect(clampHistorySize(-3)).toBe(0)
    expect(clampHistorySize(999)).toBe(200)
    expect(clampHistorySize(12.9)).toBe(12)
  })

  it('loads + clamps a stored value; non-numeric falls back to the default', () => {
    expect(loadHistorySize(fakeStore({ [KEY]: '5' }))).toBe(5)
    expect(loadHistorySize(fakeStore({ [KEY]: '999' }))).toBe(200)
    expect(loadHistorySize(fakeStore({ [KEY]: 'nope' }))).toBe(20)
  })

  it('save clamps and round-trips', () => {
    const s = fakeStore()
    expect(saveHistorySize(s, 500)).toBe(200)
    expect(loadHistorySize(s)).toBe(200)
  })
})
