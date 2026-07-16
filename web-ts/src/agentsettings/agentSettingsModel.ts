// CYP-657 (P9, Epic CYP-640) — the pure honesty core of the Per-Agent-Settings panel, port of the CMP
// AgentSettingsPanel/AgentSettingsViewModel (CYP-211/310/315). Framework-free + unit-tested; the component draws
// these. Three concerns, each with an honesty invariant:
//  1. Display colour + a CONTRAST GUARD. The identity accent is rendered as the agent's NAME text; raw CYP-14 pastels
//     are ~2:1 on the light surface (WCAG 1.4.3 fail), so senderAccent adapts them per-theme (readableAccentOn,
//     CYP-436). We port that adaptation so the preview shows the color that will ACTUALLY render (readable), not the
//     raw pick. The advisory fires only when even adaptation must collapse the colour near-monochrome to be legible
//     (identity lost) — it "rarely fires" (CMP §5.3), and an invalid hex is NOT persisted (fail-closed).
//  2. CLAUDE.md re-edit with a CONFLICT DIALOG. The SERVER version is authoritative; on drift the write returns
//     409 `claude_md_stale` and we open a merge/overwrite dialog rather than silently clobbering (fail-closed against
//     data loss). `expectedVersion` stays nullable end-to-end (null = expect-absent first write) — narrowing to "" re-
//     breaks the first write with a 409 loop (CMP AgentSettingsViewModel warns of exactly this).
//  3. Worktree path DISPLAY with the failed≠remote rule: an unresolved/failed detail load renders NOTHING (never claim
//     "not local" from an unknown), a resolved-but-null renders the honest "not local", a resolved path shows it.
// CLAUDE.md changes are RESTART-DEFERRED (take effect on the next spawn) — surfaced with the app's amber effect hint,
// never a success-green "active now" and with no restart control in this panel.
import { restErrorCode } from '../net/rest'
import { WORKER_ACCENTS_RAW } from '../ui/senderAccents.data.mjs'
import type { ClaudeMdView } from '../types/generated/contract'

// ── Colour palette ─────────────────────────────────────────────────────────────────────────────────────────────
/** The swatch palette = the single in-repo CYP-14 identity palette (also what senderAccent hashes over). Selecting a
 *  swatch stores its hex as the agent's `color` override; the render adapts it per-theme like any accent. */
export const COLOR_SWATCHES: readonly string[] = WORKER_ACCENTS_RAW

// ── Contrast guard ───────────────────────────────────────────────────────────────────────────────────────────────
/** The two theme surfaces the identity accent must read against (from tokens.generated.css: light #FFFFFF, dark
 *  #06121A). The guard checks BOTH so a colour readable in one theme but not the other is still caught. */
export const SURFACE_LIGHT = '#FFFFFF'
export const SURFACE_DARK = '#06121A'
/** WCAG 1.4.3 AA floor for the accent used as NAME text on a surface. readableAccentOn nudges to clear this. */
export const AA_TEXT_CONTRAST = 4.5
/** The floor, as a raw-colour-vs-surface contrast ratio, below which the accent melts into that theme's surface as a
 *  filled identity mark (a dot/chip you can't see). readableAccentOn can still rescue it AS TEXT (black/white are
 *  always reachable), so this is deliberately below the WCAG 1.4.11 graphical floor (3:1) — the guard targets only
 *  the near-invisible picks, not every low-AA pastel. Measured: the whole CYP-14 palette sits ≥1.77 (ok); near-white
 *  (~1.05 on light) and near-#06121A (~1.1 on dark) fall below → the "degraded" advisory. Advisory, never blocking. */
export const DISTINGUISHABLE_FLOOR = 1.4

export interface Rgb {
  r: number
  g: number
  b: number
}

/** Parse `#RGB` / `#RRGGBB` (leading # optional, case-insensitive) → rgb, or null for anything else. Null is the
 *  fail-closed signal: an unparseable hex is never persisted as a colour. */
export function parseHexColor(input: string): Rgb | null {
  const m = /^#?([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.exec(input.trim())
  if (m === null) return null
  let h = m[1]
  if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2]
  return { r: parseInt(h.slice(0, 2), 16), g: parseInt(h.slice(2, 4), 16), b: parseInt(h.slice(4, 6), 16) }
}

/** Normalize any parseable hex to canonical lower-case `#rrggbb` (so swatch equality + storage are stable). */
export function normalizeHex(input: string): string | null {
  const rgb = parseHexColor(input)
  if (rgb === null) return null
  return rgbToHex(rgb)
}

function rgbToHex({ r, g, b }: Rgb): string {
  const h = (n: number) => n.toString(16).padStart(2, '0')
  return `#${h(r)}${h(g)}${h(b)}`
}

function channelLuminance(c: number): number {
  const s = c / 255
  return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4)
}

/** WCAG relative luminance of an rgb colour. */
export function relativeLuminance(rgb: Rgb): number {
  return 0.2126 * channelLuminance(rgb.r) + 0.7152 * channelLuminance(rgb.g) + 0.0722 * channelLuminance(rgb.b)
}

/** WCAG contrast ratio between two rgb colours (order-independent, 1..21). */
export function contrastRatio(a: Rgb, b: Rgb): number {
  const la = relativeLuminance(a)
  const lb = relativeLuminance(b)
  const hi = Math.max(la, lb)
  const lo = Math.min(la, lb)
  return (hi + 0.05) / (lo + 0.05)
}

function mix(a: Rgb, b: Rgb, t: number): Rgb {
  return {
    r: Math.round(a.r + (b.r - a.r) * t),
    g: Math.round(a.g + (b.g - a.g) * t),
    b: Math.round(a.b + (b.b - a.b) * t),
  }
}

const BLACK: Rgb = { r: 0, g: 0, b: 0 }
const WHITE: Rgb = { r: 255, g: 255, b: 255 }

export interface AdaptedAccent {
  /** The adapted colour as `#rrggbb` — readable as text on `surface`. */
  hex: string
  /** 0..1 fraction the raw colour had to be mixed toward the contrasting pole to clear AA. 0 = already readable. */
  moved: number
}

/** Nudge a raw accent toward the surface's contrasting pole (black on a light surface, white on a dark one) just
 *  enough to clear the AA text floor — the port of CYP-436's readableAccentOn. Returns the adapted colour + how far
 *  it moved (the collapse signal). Null iff the raw hex is unparseable. Deterministic 2%-step scan (no float drift). */
export function readableAccentOn(rawHex: string, surfaceHex: string): AdaptedAccent | null {
  const raw = parseHexColor(rawHex)
  const surface = parseHexColor(surfaceHex)
  if (raw === null || surface === null) return null
  if (contrastRatio(raw, surface) >= AA_TEXT_CONTRAST) return { hex: rgbToHex(raw), moved: 0 }
  const pole = relativeLuminance(surface) > 0.5 ? BLACK : WHITE
  for (let step = 1; step <= 50; step++) {
    const t = step / 50
    const c = mix(raw, pole, t)
    if (contrastRatio(c, surface) >= AA_TEXT_CONTRAST) return { hex: rgbToHex(c), moved: t }
  }
  return { hex: rgbToHex(pole), moved: 1 }
}

export type ColorVerdict =
  | { kind: 'invalid' }
  | { kind: 'ok' }
  | { kind: 'degraded'; worstTheme: 'light' | 'dark'; ratio: number }

/** The contrast guard's verdict for a colour input. Invalid hex → `invalid` (not persistable). Otherwise the raw
 *  colour is scored against BOTH theme surfaces; if the WORSE-theme contrast is below DISTINGUISHABLE_FLOOR (the mark
 *  melts into that surface) the verdict is `degraded` naming the worse theme. Reasonable colours (incl. the whole
 *  CYP-14 palette) → ok. The min-fold over both themes is the guard — a colour readable on one theme but invisible on
 *  the other is still caught. */
export function evaluateColor(input: string): ColorVerdict {
  const rgb = parseHexColor(input)
  if (rgb === null) return { kind: 'invalid' }
  const onLight = contrastRatio(rgb, parseHexColor(SURFACE_LIGHT)!)
  const onDark = contrastRatio(rgb, parseHexColor(SURFACE_DARK)!)
  const worst = onLight <= onDark ? { theme: 'light' as const, ratio: onLight } : { theme: 'dark' as const, ratio: onDark }
  if (worst.ratio < DISTINGUISHABLE_FLOOR) return { kind: 'degraded', worstTheme: worst.theme, ratio: worst.ratio }
  return { kind: 'ok' }
}

// ── CLAUDE.md conflict policy ────────────────────────────────────────────────────────────────────────────────────
/** The server's 409 code for a stale CLAUDE.md write (baseline version no longer matches). The one branch that opens
 *  the conflict dialog; every other reject is a plain save error. Keyed on the machine code, never the message. */
export const CLAUDE_MD_STALE_CODE = 'claude_md_stale'

export interface ClaudeMdBaseline {
  /** The buffer as last loaded from / saved to the server (the dirty baseline). */
  baseline: string
  /** The server version of that baseline. Nullable end-to-end: null = the file was absent (expect-absent first write). */
  version: string | null
  /** Whether the file existed on the server at load. Drives expectedVersion (absent → no if-match). */
  exists: boolean
}

/** Is the edit buffer diverged from the last loaded/saved server content? */
export function isClaudeMdDirty(content: string, baseline: string): boolean {
  return content !== baseline
}

/** The if-match value to send with a write: the loaded version when the file exists, else null (expect-absent). This
 *  is the no-silent-clobber guard — a stale version → server 409 rather than an overwrite. NEVER narrow null to "":
 *  an "" expected-version on an absent file re-introduces a first-write 409 loop (CMP AgentSettingsViewModel §57-62). */
export function expectedVersion(exists: boolean, version: string | null): string | null {
  return exists ? version : null
}

/** Classify a write reject: the stale code opens the conflict dialog; anything else is a generic save error (the
 *  buffer stays dirty — never a fake "saved"). */
export function classifyWriteError(err: unknown): 'stale' | 'error' {
  return restErrorCode(err) === CLAUDE_MD_STALE_CODE ? 'stale' : 'error'
}

/** Re-sync the baseline to the server echo after a successful write (200 returns the fresh ClaudeMdView). The buffer
 *  and baseline both become the echoed content, version + exists follow the server — so `dirty` reads false and the
 *  next write carries the fresh if-match. */
export function baselineFromView(view: ClaudeMdView): ClaudeMdBaseline {
  return { baseline: view.content, version: view.version ?? null, exists: view.exists }
}

export type ClaudeMdHint = 'saveError' | 'unsaved' | 'restart' | 'empty' | null

/** Exactly one persona/CLAUDE.md disclosure, by precedence (mirrors the CMP `when` block): a save error outranks the
 *  unsaved-dirty note, which outranks the restart-deferred hint (armed only after a real save), which outranks the
 *  empty-file note. Reordering this is a RED-tested regression (a stale "saved, restart" over a failed save lies). */
export function claudeMdHint(s: { saveError: boolean; dirty: boolean; saved: boolean; empty: boolean }): ClaudeMdHint {
  if (s.saveError) return 'saveError'
  if (s.dirty) return 'unsaved'
  if (s.saved) return 'restart'
  if (s.empty) return 'empty'
  return null
}

// ── Worktree path zone ───────────────────────────────────────────────────────────────────────────────────────────
export type WorktreeZone = 'hidden' | 'notLocal' | 'path'

/** The worktree-path display zone with the failed≠remote honesty rule: an UNRESOLVED detail (load pending/failed) →
 *  `hidden` (never claim "not local" from an unknown); resolved + null → `notLocal`; resolved + a path → `path`. */
export function worktreeZone(resolved: boolean, path: string | null | undefined): WorktreeZone {
  if (!resolved) return 'hidden'
  return path ? 'path' : 'notLocal'
}

// ── Static copy (ported DE keys; keeps the components literal-free + the wording testable) ───────────────────────
export const AGENT_SETTINGS_TEXT = {
  title: 'Agenten-Einstellungen',
  agentPickerLabel: 'Agent',
  operatorRequired: 'Nur mit Operator-Token änderbar', // reuse agent_mgmt_operator_required voice
  // Colour
  colorHeading: 'Anzeigefarbe',
  colorSwatchLabel: (i: number) => `Farbe ${i + 1}`,
  customHexLabel: 'Eigener Farbwert (Hex)',
  customHexInvalid: 'Kein gültiger Hex-Farbwert (z. B. #4488CC).',
  contrastDegraded: (worst: 'light' | 'dark') =>
    `Diese Farbe verschwindet auf ${worst === 'light' ? 'hellem' : 'dunklem'} Hintergrund fast – als Agentenfarbe ist sie dort kaum zu erkennen. Bitte eine kräftigere Farbe wählen.`,
  previewLabel: 'Vorschau',
  saveColor: 'Farbe speichern',
  // Persona / CLAUDE.md
  personaHeading: 'Persona / CLAUDE.md',
  personaLoadError: 'CLAUDE.md konnte nicht geladen werden.',
  personaRetry: 'Erneut laden',
  personaEmpty: 'Für diesen Agenten ist noch keine CLAUDE.md hinterlegt.',
  personaUnsaved: 'Nicht gespeicherte Änderungen.',
  personaSaveError: 'Speichern fehlgeschlagen.',
  // amber "saved ≠ active" — reuse the exact app string (agent_edit_effect_hint), never success-green, no restart here.
  effectHint:
    'Gespeichert. Wirkt erst beim nächsten Start des Agenten – jetzt neu starten, damit die neue Konfiguration zieht.',
  savePersona: 'CLAUDE.md speichern',
  // Conflict dialog (409 claude_md_stale)
  conflictTitle: 'CLAUDE.md wurde zwischenzeitlich geändert',
  conflictBody:
    'Die Server-Version dieser CLAUDE.md hat sich geändert, seit du sie geladen hast. Überschreiben verwirft die andere Änderung. Du kannst stattdessen die aktuelle Version laden und deine Änderung neu einarbeiten.',
  conflictOverwrite: 'Trotzdem überschreiben',
  conflictReload: 'Aktuelle Version laden',
  // Worktree
  worktreeHeading: 'Worktree-Pfad',
  worktreeNotLocal: 'Kein lokaler Worktree für diesen Agenten.',
  worktreeCopy: 'Pfad kopieren',
  worktreeCopied: 'Kopiert',
} as const

/** The testid contract, ported 1:1 from the CMP AgentSettingsTags (dotted, area-prefixed). */
export const AGENT_SETTINGS_TESTID = {
  panel: 'agentSettings.panel',
  gate: 'agentSettings.gateHint',
  agentPicker: 'agentSettings.agentPicker',
  color: 'agentSettings.color',
  swatch: (i: number) => `agentSettings.swatch.${i}`,
  customHexInput: 'agentSettings.customHex.input',
  customHexError: 'agentSettings.customHex.error',
  contrastAdvisory: 'agentSettings.contrastAdvisory',
  preview: 'agentSettings.preview',
  saveColor: 'agentSettings.color.save',
  persona: 'agentSettings.persona.input',
  personaLoadError: 'agentSettings.persona.loadError',
  personaRetry: 'agentSettings.persona.loadError.retry',
  personaEmpty: 'agentSettings.persona.empty',
  personaUnsaved: 'agentSettings.persona.unsaved',
  personaSaveError: 'agentSettings.persona.saveError',
  personaRestart: 'agentSettings.persona.restart',
  effectHint: 'agentSettings.effectHint',
  savePersona: 'agentSettings.persona.save',
  conflict: 'agentSettings.persona.conflict',
  conflictOverwrite: 'agentSettings.persona.conflict.overwrite',
  conflictReload: 'agentSettings.persona.conflict.reload',
  worktree: 'agentSettings.worktree.path',
  worktreeNotLocal: 'agentSettings.worktree.notLocal',
  worktreeCopy: 'agentSettings.worktree.copy',
} as const
