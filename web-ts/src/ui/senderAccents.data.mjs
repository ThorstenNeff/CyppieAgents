// CYP-436 — the raw CYP-14 identity accent palette (dark-tuned nameAccent values, from :app:shared SenderPalette).
// SINGLE source: the token generator adapts these per-theme (readableAccentOn) into `--sender-accent-*` CSS vars,
// and the a11y guard measures the same values. The PO/hub gets a reserved slot so it never collides with a worker.
// These hues deliberately avoid the CYP-12 status hues (blue/green/red/amber) so identity never reads as status.
export const PO_ACCENT_RAW = '#A6A9F0'
export const WORKER_ACCENTS_RAW = [
  '#5FD0BE', // 0 teal
  '#B9A4F2', // 1 violet
  '#E693D2', // 2 magenta
  '#8FAAEF', // 3 indigo
  '#D9AE6E', // 4 bronze
  '#F0A0B3', // 5 pink
  '#9EB8D6', // 6 slateblue
  '#BFC97E', // 7 olive
]
