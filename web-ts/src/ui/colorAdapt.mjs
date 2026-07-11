// CYP-436 — faithful port of `:core` ColorDerivation.readableAccentOn (+ its darken/lighten/contrast helpers).
// The CYP-14 identity pastels are calibrated for the DARK surface (they clear the 4.5:1 text floor on navy) and
// wash out on the LIGHT (white) surface (~2:1). Compose adapts them per-scheme (readableNameAccent); the web-ts
// port dropped that. This restores it: hue-preserving darken (toward black) / lighten (toward white) in 6% steps
// until the accent clears the text floor on the ACTUAL surface — measured, not eyeballed. Kept as .mjs (+ .d.mts)
// so the token-CSS generator computes the per-theme adapted values from the same code the a11y guard measures.
const CONTRAST_TEXT_MIN = 4.5
const MAX_STEPS = 24

const clamp = (n) => Math.max(0, Math.min(255, Math.trunc(n)))
const hexToRgb = (hex) => {
  const n = parseInt(hex.slice(1), 16)
  return [(n >> 16) & 0xff, (n >> 8) & 0xff, n & 0xff]
}
const rgbToHex = (r, g, b) =>
  '#' + [clamp(r), clamp(g), clamp(b)].map((c) => c.toString(16).padStart(2, '0')).join('').toUpperCase()

const srgbToLinear = (c) => {
  const s = c / 255
  return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
}
export function relLuminance(hex) {
  const [r, g, b] = hexToRgb(hex)
  return 0.2126 * srgbToLinear(r) + 0.7152 * srgbToLinear(g) + 0.0722 * srgbToLinear(b)
}
export function contrastRatio(a, b) {
  const la = relLuminance(a)
  const lb = relLuminance(b)
  const hi = Math.max(la, lb)
  const lo = Math.min(la, lb)
  return (hi + 0.05) / (lo + 0.05)
}

// darken: scale each channel by `factor`. lighten: lerp each channel toward white by `t`. Both from the ORIGINAL
// accent per step (cumulative factor), exactly as `:core` does — so the ladder is deterministic.
const darken = (hex, factor) => {
  const [r, g, b] = hexToRgb(hex)
  return rgbToHex(r * factor, g * factor, b * factor)
}
const lighten = (hex, t) => {
  const [r, g, b] = hexToRgb(hex)
  return rgbToHex(r + (255 - r) * t, g + (255 - g) * t, b + (255 - b) * t)
}

/** The accent adapted to clear the 4.5:1 text floor on `surface` (hue preserved); returns it unchanged if it
 *  already clears. Surface lighter than the accent → darken; else lighten. */
export function readableAccentOn(accent, surface) {
  if (contrastRatio(accent, surface) >= CONTRAST_TEXT_MIN) return accent
  const darker = relLuminance(surface) > relLuminance(accent)
  let c = accent
  let step = 0
  while (contrastRatio(c, surface) < CONTRAST_TEXT_MIN && step < MAX_STEPS) {
    c = darker ? darken(accent, 1.0 - 0.06 * (step + 1)) : lighten(accent, 0.06 * (step + 1))
    step++
  }
  return c
}
