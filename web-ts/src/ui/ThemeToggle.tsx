// CYP-643 (P3, Epic CYP-640) — the app-global theme switcher in the workspace bar. Parity with CMP ThemeModeToggle
// (CYP-268): System / Light / Dark, client-local + per-user (NOT operator-gated, NOT project-scoped). A native
// <select> is the accessible, keyboard-native web idiom — the selected option IS the active-mode marker (parity of
// CMP's ● active mark). Each option carries a fill-fraction glyph (◐ System / ○ Hell / ● Dunkel) so the choice is
// never colour-only (WCAG 1.4.1). This only DRIVES the mode via onChange; App owns the persist + recolour.
import type { ThemeMode } from './themePreference'
import { THEME_MODES } from './themePreference'

const LABELS: Record<ThemeMode, string> = {
  system: '◐ System',
  light: '○ Hell',
  dark: '● Dunkel',
}

export function ThemeToggle({ mode, onChange }: { mode: ThemeMode; onChange: (mode: ThemeMode) => void }) {
  return (
    <label className="theme-toggle" data-testid="theme-toggle">
      <span className="theme-toggle-label">Thema</span>
      <select
        data-testid="theme-toggle.select"
        aria-label="Thema (App-Farbschema)"
        value={mode}
        onChange={(e) => onChange(e.target.value as ThemeMode)}
      >
        {THEME_MODES.map((m) => (
          <option key={m} value={m}>
            {LABELS[m]}
          </option>
        ))}
      </select>
    </label>
  )
}
