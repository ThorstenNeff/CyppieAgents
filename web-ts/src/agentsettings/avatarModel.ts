// CYP-658 (P10, Epic CYP-640) — the pure honesty core of the Agent-Avatar section, port of the CMP
// AgentAvatarSection / AgentSettingsViewModel avatar path (CYP-215/216). Framework-free + unit-tested; the component
// draws these. It integrates as an Avatar section inside the P9 AgentSettingsPanel (after CYP-657 merges), so testids
// keep the shared `agentSettings.avatar.*` contract (mirrors the CMP AgentSettingsTags) but live self-contained here.
//
// Honesty invariants:
//  - Upload is FAIL-CLOSED and pre-checked client-side before any network call: a disallowed MIME → `type`, an
//    oversize blob → `size`; only `ok` uploads. An invalid pick is NEVER persisted (the server re-checks by magic
//    bytes too — this pre-check is the fast, honest first gate, not the security boundary).
//  - The avatar STATE the UI shows is the SERVER's truth (non-optimistic): a preset write / upload returns the fresh
//    Agent/AgentDetail and the caller re-syncs from it; the serve <img> points at the same-origin server route
//    (never api.dicebear.com — no third-party egress), so it shows the ACTUALLY stored PNG, not a placeholder.
//  - Writes can only carry a Preset (the contract's AgentEdit.avatar is Preset-only) — a client cannot forge an Upload;
//    Upload refs are server-minted.
// Backend2 server-truth (CYP-658, cyp658-avatar-upload-server-truth.md) — three invariants this model binds to:
//  1. `avatar_rejected` is ONE uniform 400 with NO reason (no oracle). A SERVER-side reject → the GENERIC message only;
//     NEVER derive a specific "wrong type/too big" the server did not confirm (derivation-masquerading trap). The
//     client pre-check (precheckUpload) MAY say type/size — the client itself checked the real bytes it holds — but a
//     network reject after a passing pre-check is `generic`, full stop.
//  2. There is NO server preview of an upload before commit: the upload IS the commit (server re-crops to 256×256 +
//     strips EXIF). `/avatar/preview?style=&seed=` is the DiceBear PRESET grid ONLY. Any pre-commit upload thumbnail is
//     a client-local createObjectURL APPROXIMATION — labelled as such, never claimed pixel-exact to what is stored.
//  3. DELETE /avatar is 204 idempotent with no confirm token → the "reset to default?" confirm is the CLIENT's to own.
import type { AgentAvatar, Preset } from '../types/generated/contract'

// ── DiceBear preset styles (the curated 5; data, mirrors CMP AVATAR_STYLES + the server ALLOWED_STYLES allow-list) ──
export type AvatarStyle = 'bottts' | 'avataaars' | 'adventurer' | 'big-smile' | 'fun-emoji'

export interface AvatarStyleInfo {
  style: AvatarStyle
  /** Human label for the cell/credit line. */
  label: string
  /** Artist to attribute. */
  artist: string
  /** License text (SPDX-less courtesy grant, or "CC BY 4.0"). */
  license: string
  /** The license URL — CC BY 4.0 §3(a) requires the URI; shown as a clickable link. */
  licenseUrl: string
  /** CC-BY styles need attribution + a "(bearbeitet)" suffix (the served PNG strips DiceBear's embedded credit). */
  ccBy: boolean
}

export const AVATAR_STYLES: readonly AvatarStyleInfo[] = [
  { style: 'bottts', label: 'Bottts', artist: 'Pablo Stanley', license: 'Frei für privat & kommerziell', licenseUrl: 'https://bottts.com/', ccBy: false },
  { style: 'avataaars', label: 'Avataaars', artist: 'Pablo Stanley', license: 'Frei für privat & kommerziell', licenseUrl: 'https://avataaars.com/', ccBy: false },
  { style: 'adventurer', label: 'Adventurer', artist: 'Lisa Wischofsky', license: 'CC BY 4.0', licenseUrl: 'https://creativecommons.org/licenses/by/4.0/', ccBy: true },
  { style: 'big-smile', label: 'Big Smile', artist: 'Ashley Seo', license: 'CC BY 4.0', licenseUrl: 'https://creativecommons.org/licenses/by/4.0/', ccBy: true },
  { style: 'fun-emoji', label: 'Fun Emoji', artist: 'Davis Uche', license: 'CC BY 4.0', licenseUrl: 'https://creativecommons.org/licenses/by/4.0/', ccBy: true },
]

/** A credit line for a style: `<Label> von <Artist> · <License>` plus " (bearbeitet)" for CC-BY (modified) styles.
 *  All five are credited (bottts/avataaars as a courtesy per the PO directive); only CC-BY carries the suffix. */
export function creditLine(info: AvatarStyleInfo): string {
  return `${info.label} von ${info.artist} · ${info.license}${info.ccBy ? ' (bearbeitet)' : ''}`
}

// ── Upload pre-check (fail-closed, UX-only pre-flight) ───────────────────────────────────────────────────────────
// ⚠ DRIFT DISCIPLINE (Backend2, CYP-658 — same class as the P8 ProjectGuard / Compact-Bounds mirror): the server's
// `AvatarLimits` (5 MB + PNG/JPEG allow-list + 4xx codes) is JVM-server-only; only the AVATAR DTO is in :core, so these
// two constants are a HAND-MIRRORED copy with NO compiler link. They are a UX-only PRE-FLIGHT — the server's magic-byte
// sniff on the real bytes is authoritative. If the server richtwerte change, sync these by hand. Drift is safe either
// way: stricter-here → a stale-UX reject (annoying, nothing wrong persisted); looser-here → the server 400s
// `avatar_rejected` → we show the GENERIC error (fail-closed) — never a wrong value through.
export const ALLOWED_UPLOAD_MIME: readonly string[] = ['image/png', 'image/jpeg', 'image/jpg']
export const MAX_UPLOAD_BYTES = 5 * 1024 * 1024
export const MAX_UPLOAD_LABEL = '5 MB'

export type UploadPrecheck = 'ok' | 'type' | 'size'

/** The client pre-check gate: a disallowed MIME → `type`, an oversize blob → `size`, else `ok`. Fail-closed — the
 *  caller uploads ONLY on `ok` (a non-ok verdict shows the error and never touches the network / never persists).
 *  Type is checked before size so an unsupported format reports as such even when it is also oversize. */
export function precheckUpload(mime: string, sizeBytes: number): UploadPrecheck {
  if (!ALLOWED_UPLOAD_MIME.includes(mime.toLowerCase())) return 'type'
  if (sizeBytes > MAX_UPLOAD_BYTES) return 'size'
  return 'ok'
}

/** The upload error kinds surfaced in the UI: the two pre-check verdicts plus `generic` for a server-side reject
 *  (avatar_rejected / avatar_no_file / network) that the pre-check could not foresee. */
export type AvatarUploadError = 'type' | 'size' | 'generic'

// ── Avatar stage (DTO-derived fallback chain) ────────────────────────────────────────────────────────────────────
/** The effective render stage, derived purely from the DTO (never a pixel peek): an Upload → `image`, a Preset →
 *  `preset`, otherwise the name drives `initials` (non-blank) / `color` (blank). Drives the fallback chain + the
 *  a11y stateDescription. */
export type AvatarStage = 'image' | 'preset' | 'initials' | 'color'

export function avatarStage(avatar: AgentAvatar | null | undefined, name: string): AvatarStage {
  if (avatar?.type === 'upload') return 'image'
  if (avatar?.type === 'preset') return 'preset'
  return name.trim() === '' ? 'color' : 'initials'
}

/** The currently-selected preset style, or null when the avatar is an Upload / unset (used to light the grid cell). */
export function selectedStyleOf(avatar: AgentAvatar | null | undefined): AvatarStyle | null {
  return avatar?.type === 'preset' ? (avatar.style as AvatarStyle) : null
}

// ── Preset builders (the write payloads; seed = agent id is the deterministic default) ───────────────────────────
/** A fresh Preset for a style, seeded by the agent id (the deterministic default — same agent → same art). */
export function presetFor(style: AvatarStyle, agentId: string): Preset {
  return { style, seed: agentId, type: 'preset' }
}

/** A shuffled Preset within the same style: seed = `<agentId>-<nonce>` (the caller supplies the nonce, e.g.
 *  Math.floor(Math.random()*1_000_000), so this stays pure/testable). */
export function shuffledPreset(style: AvatarStyle, agentId: string, nonce: number): Preset {
  return { style, seed: `${agentId}-${nonce}`, type: 'preset' }
}

// ── Same-origin image URLs (NEVER api.dicebear.com — no third-party egress) ──────────────────────────────────────
/** The cache-bust version token for the serve URL: an Upload → its `ref`, a Preset → `<style>-<seed>` (so a
 *  re-shuffle/new-preset busts the browser cache). */
export function avatarVersionToken(avatar: AgentAvatar): string {
  return avatar.type === 'upload' ? avatar.ref : `${avatar.style}-${avatar.seed}`
}

/** The serve URL for the agent's CURRENT avatar (`?v=` cache-bust). Same-origin server route only. */
export function avatarServeUrl(apiBase: string, agentId: string, avatar: AgentAvatar): string {
  return `${apiBase}/api/agents/${encodeURIComponent(agentId)}/avatar?v=${encodeURIComponent(avatarVersionToken(avatar))}`
}

/** The preview URL for a candidate preset (grid cell) — the server renders the actual PNG (non-optimistic). */
export function avatarPreviewUrl(apiBase: string, agentId: string, style: string, seed: string): string {
  return `${apiBase}/api/agents/${encodeURIComponent(agentId)}/avatar/preview?style=${encodeURIComponent(style)}&seed=${encodeURIComponent(seed)}`
}

// ── Static copy (ported DE keys) + testid contract (mirrors the CMP AgentSettingsTags avatar block) ─────────────
export const AVATAR_TEXT = {
  heading: 'Avatar',
  cropHint: 'Das Bild wird serverseitig quadratisch auf 256×256 zugeschnitten und EXIF-Daten werden entfernt.',
  uploadButton: 'Bild hochladen',
  shuffleButton: 'Neu würfeln',
  removeButton: 'Avatar entfernen',
  creditsHeading: 'Bildnachweis',
  uploadTypeError: 'Nicht unterstütztes Format – bitte PNG oder JPEG.',
  uploadSizeError: `Bild ist zu groß – höchstens ${MAX_UPLOAD_LABEL}.`,
  // Backend2 §1: a server reject is a UNIFORM code with no reason — show ONLY this generic line, never a fabricated why.
  uploadGenericError: 'Hochladen fehlgeschlagen.',
  // Backend2 §2: a pre-commit thumbnail is a client-local approximation, NOT the stored result — labelled honestly.
  uploadApproxNote: 'Vorschau (ungefähr) – gespeichert wird das serverseitig auf 256×256 zugeschnittene Bild.',
  presetStyleLabel: (label: string) => `Stil ${label}`,
  // Backend2 §3: DELETE is idempotent with no server confirm → the client owns the "reset to default?" confirm.
  removeConfirm: 'Avatar entfernen und auf den Standard (Initialen/Farbe) zurücksetzen?',
  removeConfirmButton: 'Entfernen',
  cancel: 'Abbrechen',
} as const

/** The upload-error copy for a given error kind (size names the limit). */
export function uploadErrorText(kind: AvatarUploadError): string {
  switch (kind) {
    case 'type':
      return AVATAR_TEXT.uploadTypeError
    case 'size':
      return AVATAR_TEXT.uploadSizeError
    case 'generic':
      return AVATAR_TEXT.uploadGenericError
  }
}

export const AVATAR_TESTID = {
  section: 'agentSettings.avatar.section',
  current: 'agentSettings.avatar.current',
  preset: 'agentSettings.avatar.preset',
  presetStyle: (style: string) => `agentSettings.avatar.presetStyle.${style}`,
  shuffle: 'agentSettings.avatar.shuffle',
  upload: 'agentSettings.avatar.upload',
  remove: 'agentSettings.avatar.remove',
  credits: 'agentSettings.avatar.credits',
  creditEntry: (style: string) => `agentSettings.avatar.credits.entry-${style}`,
  cropHint: 'agentSettings.avatar.cropHint',
  uploadError: 'agentSettings.avatar.uploadError',
  uploadApprox: 'agentSettings.avatar.uploadApprox',
  removeConfirm: 'agentSettings.avatar.removeConfirm',
  removeConfirmButton: 'agentSettings.avatar.removeConfirm.confirm',
  removeCancel: 'agentSettings.avatar.removeConfirm.cancel',
  fileInput: 'agentSettings.avatar.fileInput',
} as const
