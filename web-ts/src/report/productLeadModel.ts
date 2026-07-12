// CYP-464 (P2-d) — Product-Lead report honesty core (pure). Ported from Compose ProductLeadViewModel/ReportModel.
// The one rule the whole surface exists to keep (spec §0): a snapshot is a point-in-time OBSERVATION, never a
// standing/live state — every snapshot carries a prominent "As of: <ts>" + a may-be-outdated hint + provenance
// (named sources + observation window). DEFECTS are advisory (observed, not exhaustive/confirmed), never authoritative.
// Severity reuses the ONE house source (eventLog severityGlyph/Label) — colour never the sole carrier (§7).
import type { ReportSnapshot } from '../types/generated/contract'
export { severityGlyph, severityLabel, type Severity } from '../eventlog/eventLog'

export type ReportType = 'usage' | 'status' | 'defects'
export const REPORT_TYPES: readonly ReportType[] = ['usage', 'status', 'defects']

export function reportTypeLabel(type: ReportType): string {
  switch (type) {
    case 'usage':
      return 'Nutzungs-Leitfaden' // report_type_usage
    case 'status':
      return 'Status-Report' // report_type_status
    case 'defects':
      return 'Defekt-/Lücken-Register' // report_type_defects
  }
}

/** "Stand: <local datetime>" — the prominent as-of stamp (list row + detail heading). A snapshot is never live. */
export function asOfLabel(generatedAt: number): string {
  return `Stand: ${new Date(generatedAt).toLocaleString()}` // report_as_of
}

/** Provenance: the NAMED sources + observation window — "observed, not authoritative", never invented completeness. */
export function provenanceText(snapshot: ReportSnapshot): string {
  const sources = snapshot.sources.length > 0 ? snapshot.sources.join(', ') : '—'
  const w = snapshot.window
  const win = w && (w.sinceLabel || w.untilLabel) ? ` · Fenster ${w.sinceLabel ?? '…'}–${w.untilLabel ?? '…'}` : ''
  return `Beobachtet aus: ${sources}${win}` // report_provenance
}

export const REPORT_TEXT = {
  title: 'Product-Lead', // report_title
  generate: 'Bericht erzeugen', // report_generate
  generating: 'Wird erzeugt…', // report_generating (neutral, never green — in-progress is not success)
  error: 'Bericht-Erzeugung fehlgeschlagen', // report_error
  empty: 'Keine Reports', // report_empty (only when truly loaded AND empty)
  gateHint: 'Nur mit Operator-Token verfügbar', // report_access_denied (GATED neutral, never green — a gate, not an error)
  snapshotHint: 'Momentaufnahme – kann veraltet sein.', // report_snapshot_hint (Snapshot ≠ Live)
  // DEFECTS register is advisory — observed, not exhaustive/confirmed; never "all defects", never authoritative.
  advisory: 'Beobachtet, nicht vollständig oder bestätigt.', // report_advisory
  back: '‹ Zurück', // comm_back (reuse)
} as const
