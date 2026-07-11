// CYP-407 (W9) / CYP-436 — sender identity accent, port of :app:shared's SenderPalette (CYP-14). Returns a
// theme-adaptive CSS VARIABLE (not a fixed hex): `--sender-accent-*` is defined per-theme in the generated token
// layer, where each CYP-14 pastel is adapted (readableAccentOn) to clear the 4.5:1 text floor on that theme's
// surface. This fixes CYP-436: the raw dark-tuned pastels are ~2:1 as text on the light surface (WCAG 1.4.3 fail);
// the port had dropped Compose's scheme-adaptive readableNameAccent. The PO/hub gets a reserved slot so it never
// collides with a worker; identity is also carried by text/PO-badge (a11y) — colour is never the sole signal (1.4.1).
import { WORKER_ACCENTS_RAW } from '../ui/senderAccents.data.mjs'

const SLOT_COUNT = WORKER_ACCENTS_RAW.length

/** Stable non-negative slot for an id (deterministic; identity, not a wire contract — not byte-identical to Compose). */
function slot(id: string, n: number): number {
  let h = 0
  for (let i = 0; i < id.length; i++) h = (Math.imul(h, 31) + id.charCodeAt(i)) | 0
  return ((h % n) + n) % n
}

/** A `var(--sender-accent-…)` reference resolving to the per-theme adapted accent (CYP-436). PO → reserved slot. */
export function senderAccent(id: string, role?: string | null): string {
  const key = role === 'PO' || id === 'po' ? 'po' : String(slot(id, SLOT_COUNT))
  return `var(--sender-accent-${key})`
}
