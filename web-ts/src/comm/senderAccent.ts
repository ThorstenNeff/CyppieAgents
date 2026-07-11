// CYP-407 (W9) — sender identity accent, port of :app:shared's SenderPalette (CYP-14). A FIXED, contrast-tuned
// palette (the CYP-14 nameAccent values, chosen to clear the readable floor on the surface) keyed by a stable
// hash of the sender id; the PO/hub gets a reserved slot so it never collides with a worker. Identity is also
// carried by text/avatar/PO-badge (a11y) — colour is never the sole signal (WCAG 1.4.1).
const PO_ACCENT = '#A6A9F0'
const ACCENTS = ['#5FD0BE', '#B9A4F2', '#E693D2', '#8FAAEF', '#D9AE6E', '#F0A0B3', '#9EB8D6', '#BFC97E']

/** Stable non-negative slot for an id (deterministic; identity, not a wire contract — not byte-identical to Compose). */
function slot(id: string, n: number): number {
  let h = 0
  for (let i = 0; i < id.length; i++) h = (Math.imul(h, 31) + id.charCodeAt(i)) | 0
  return ((h % n) + n) % n
}

export function senderAccent(id: string, role?: string | null): string {
  if (role === 'PO' || id === 'po') return PO_ACCENT
  return ACCENTS[slot(id, ACCENTS.length)]
}
