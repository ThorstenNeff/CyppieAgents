// CYP-423 (Maritime token layer) — the TS view of the token source (maritimeTokens.data.mjs) plus the WCAG
// contrast utility the a11y guard uses. The CSS itself is GENERATED from the same source (scripts/generate-tokens-
// css.mjs → src/ui/tokens.generated.css, "generate, don't commit") so the shipped `--md-sys-color-*` values and the
// values this guard measures cannot drift. `cssVarName` is the ONE place the camelCase role → M3 kebab var name
// mapping lives (shared conceptually with the generator, which mirrors it).
import {
  MARITIME_TOKENS,
  EVENT_SEVERITY,
  type MaritimeRole,
  type MaritimeScheme,
  type EventSeverityKey,
  type EventSeverityScheme,
} from './maritimeTokens.data.mjs'

export { MARITIME_TOKENS, EVENT_SEVERITY }
export type { MaritimeRole, MaritimeScheme, EventSeverityKey, EventSeverityScheme }

export const MARITIME_ROLES = Object.keys(MARITIME_TOKENS.light) as MaritimeRole[]

/** camelCase role → M3 CSS custom-property name, e.g. onSurfaceVariant → --md-sys-color-on-surface-variant. */
export function cssVarName(role: MaritimeRole): string {
  const kebab = role.replace(/([A-Z])/g, '-$1').toLowerCase()
  return `--md-sys-color-${kebab}`
}

const srgbToLinear = (c: number): number => {
  const s = c / 255
  return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
}

/** WCAG relative luminance of a #rrggbb colour. */
export function relativeLuminance(hex: string): number {
  const n = parseInt(hex.slice(1), 16)
  const r = (n >> 16) & 0xff
  const g = (n >> 8) & 0xff
  const b = n & 0xff
  return 0.2126 * srgbToLinear(r) + 0.7152 * srgbToLinear(g) + 0.0722 * srgbToLinear(b)
}

/** WCAG contrast ratio (1..21) between two #rrggbb colours. */
export function contrastRatio(a: string, b: string): number {
  const la = relativeLuminance(a)
  const lb = relativeLuminance(b)
  const hi = Math.max(la, lb)
  const lo = Math.min(la, lb)
  return (hi + 0.05) / (lo + 0.05)
}

/** WCAG 1.4.11 floor for non-text graphical objects (UI components / their boundaries). */
export const WCAG_NON_TEXT_MIN = 3
