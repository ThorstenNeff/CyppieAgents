// CYP-643 — the pure theme-preference policy. Teeth pin: default/invalid → system, round-trip persistence, the
// data-theme mapping (system → clear, light/dark → stamp), and that apply is the ONLY thing that touches the root.
import { describe, it, expect, vi } from 'vitest'
import {
  loadThemeMode,
  saveThemeMode,
  dataThemeFor,
  applyThemeMode,
  DEFAULT_THEME_MODE,
  type ThemeMode,
  type ThemeRoot,
} from './themePreference'
import type { KvStore } from '../agentview/historySizePreference'

const memStore = (init: Record<string, string> = {}): KvStore => {
  const m = new Map(Object.entries(init))
  return { getItem: (k) => m.get(k) ?? null, setItem: (k, v) => void m.set(k, v) }
}

describe('loadThemeMode', () => {
  it('defaults to system when unset', () => {
    expect(loadThemeMode(memStore())).toBe('system')
    expect(DEFAULT_THEME_MODE).toBe('system')
  })
  it('falls back to system on an unrecognised stored value (fail-safe, never throws)', () => {
    // RED if a garbage value is trusted through — the mode must always be one of the three.
    expect(loadThemeMode(memStore({ 'cyppie.theme.mode': 'neon' }))).toBe('system')
  })
  it('reads a valid stored mode', () => {
    expect(loadThemeMode(memStore({ 'cyppie.theme.mode': 'dark' }))).toBe('dark')
  })
})

describe('saveThemeMode round-trip', () => {
  it('persists then reloads the same mode', () => {
    const store = memStore()
    saveThemeMode(store, 'light')
    expect(loadThemeMode(store)).toBe('light')
  })
})

describe('dataThemeFor', () => {
  it('system → null (no attribute → prefers-color-scheme governs); light/dark → explicit override', () => {
    // RED if system stamps an attribute (it must clear it so the media query can win).
    expect(dataThemeFor('system')).toBeNull()
    expect(dataThemeFor('light')).toBe('light')
    expect(dataThemeFor('dark')).toBe('dark')
  })
})

describe('applyThemeMode', () => {
  const fakeRoot = () => {
    const root: ThemeRoot & { attr: string | null } = {
      attr: null,
      setAttribute: vi.fn((_n: string, v: string) => void (root.attr = v)),
      removeAttribute: vi.fn(() => void (root.attr = null)),
    }
    return root
  }

  it('light/dark STAMP data-theme; system CLEARS it', () => {
    const dark = fakeRoot()
    applyThemeMode('dark', dark)
    expect(dark.setAttribute).toHaveBeenCalledWith('data-theme', 'dark')

    const sys = fakeRoot()
    applyThemeMode('system', sys)
    expect(sys.removeAttribute).toHaveBeenCalledWith('data-theme')
    expect(sys.setAttribute).not.toHaveBeenCalled()
  })

  it('is exhaustive over the three modes', () => {
    const modes: ThemeMode[] = ['system', 'light', 'dark']
    for (const m of modes) {
      const r = fakeRoot()
      applyThemeMode(m, r)
      expect(r.attr).toBe(m === 'system' ? null : m)
    }
  })
})
