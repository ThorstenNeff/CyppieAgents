// CYP-643 (P3, Epic CYP-640) — the app-global theme mode (system / light / dark), a client-local, per-user, durable
// preference. Parity with the CMP ThemeModeToggle (CYP-268): the toggle only DRIVES the mode; this seam owns the
// persistence + the recolour. Recolour is CSS-native: tokens.generated.css already carries `:root` (light base),
// `@media (prefers-color-scheme: dark) :root:not([data-theme='light'])` (system dark), and
// `:root[data-theme='dark'|'light']` (explicit override that wins BOTH directions). So applying a mode is just
// stamping (or clearing) `data-theme` on the root element — no per-component recolour.
//
// Mirrors the historySizePreference KV pattern (localStorage, injectable store for tests). NOT operator-gated /
// project-scoped — theme is personal.
import { type KvStore, browserStore } from '../agentview/historySizePreference'

export type ThemeMode = 'system' | 'light' | 'dark'

export const THEME_MODES: readonly ThemeMode[] = ['system', 'light', 'dark']
export const DEFAULT_THEME_MODE: ThemeMode = 'system'

const KEY = 'cyppie.theme.mode'

const isThemeMode = (v: string | null): v is ThemeMode => v === 'system' || v === 'light' || v === 'dark'

/** Load the durable mode; anything unrecognised (or unset) falls back to `system` (the honest default). */
export function loadThemeMode(store: KvStore): ThemeMode {
  const raw = store.getItem(KEY)
  return isThemeMode(raw) ? raw : DEFAULT_THEME_MODE
}

export function saveThemeMode(store: KvStore, mode: ThemeMode): void {
  store.setItem(KEY, mode)
}

/** The `data-theme` value to stamp for a mode, or `null` for `system` (no attribute → fall back to
 *  prefers-color-scheme). Explicit light/dark win over the media query in both directions (see tokens CSS). */
export function dataThemeFor(mode: ThemeMode): 'light' | 'dark' | null {
  return mode === 'system' ? null : mode
}

/** The minimal element surface applyThemeMode needs — real `document.documentElement` in the app, a fake in tests. */
export interface ThemeRoot {
  setAttribute(name: string, value: string): void
  removeAttribute(name: string): void
}

/** Apply a mode by stamping/clearing `data-theme` on [root]. `system` clears it (→ prefers-color-scheme governs);
 *  light/dark stamp the explicit override. Idempotent. */
export function applyThemeMode(mode: ThemeMode, root: ThemeRoot): void {
  const value = dataThemeFor(mode)
  if (value === null) root.removeAttribute('data-theme')
  else root.setAttribute('data-theme', value)
}

export { browserStore }
