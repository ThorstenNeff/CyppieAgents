// CYP-453 (P2-f) — the Settings-level honesty helpers for the Repo-Section (the API-key section's leak model lives in
// CYP-433, not here). Text ported 1:1 from the DE keys (project-settings-keys, 0 new). The repo save reject is
// server-authoritative (invalid_repo_url) — mapped from the RestError code, never a client string-match.
import { restErrorCode } from '../net/rest'
import type { AtRiskAgent } from '../types/generated/contract'

/** Save is enabled only for an operator with a non-empty URL (the server is still the final validator). */
export function canSaveRepo(operator: boolean, url: string): boolean {
  return operator && url.trim() !== ''
}

/** Repo save reject: invalid_repo_url → the specific "invalid URL" message; anything else → the generic failure. */
export function repoSaveRejectMessage(err: unknown): string {
  switch (restErrorCode(err)) {
    case 'invalid_repo_url':
      return 'Ungültige Repository-URL' // settings_repo_url_invalid
    default:
      return 'Speichern fehlgeschlagen' // generic
  }
}

export const SETTINGS_TEXT = {
  title: 'Projekt-Einstellungen', // settings_title — says "Projekt", marking the boundary to personal prefs
  repoSection: 'Repository', // settings_repo_section
  apiKeySection: 'API-Schlüssel', // settings_apikey_section (the section heading; interna = CYP-433)
  operatorRequired: 'Nur mit Operator-Token änderbar', // settings_operator_required / workspace_operator_only
  save: 'Speichern', // settings_save
  urlLabel: 'Repository-URL', // settings_repo_url_label
  branchLabel: 'Branch', // settings_repo_branch_label
  // honest unset: names the CONSEQUENCE (agents can't start), not just "empty" — never silently omitted.
  statusUnset: 'Kein Repository konfiguriert – Agenten können nicht starten.', // settings_repo_status_unset
  // amber "saved ≠ active" — same disclosure pattern as the API-key hint (§5), never success-green, never ellipsis.
  repoEffectHint:
    'Änderung wirkt auf neu angelegte Worktrees / beim nächsten Hochfahren – bestehende Worktrees bleiben unverändert.', // settings_repo_effect_hint
  // CYP-465 (P2-h) — new keys (not a port; land with the impl). pending ≠ applied; discard is default-safe + confirmed.
  reprovisionPending: 'Repo-Änderung steht an – wirkt beim nächsten (Neu-)Start der Agenten.', // settings_repo_reprovision_pending
  discardLabel: 'Nicht gepushte Agenten-Arbeit beim Re-Provisionieren verwerfen', // settings_repo_discard_label
  discardTitle: 'Nicht gepushte Arbeit verwerfen?',
  discardWarning:
    'Unwiderruflich: nicht committete/gepushte Arbeit in den Agenten-Worktrees geht verloren, wenn die Repo-Änderung greift.', // settings_repo_discard_warning
  // when the backend can't name the affected work yet (unknown), the warning is advisory ("if…"), never a sure claim (§3).
  discardAdvisory: 'Falls Agenten dann nicht gepushte Arbeit haben, geht sie verloren.',
  // loaded-empty is AUTHORITATIVE "zero risk" (atRisk is contract-required), NOT "unknown" → no destructive discard (§6.5).
  discardCleared: 'Keine nicht gepushte Arbeit gefunden – nichts zu verwerfen.', // settings_repo_discard_cleared
  discardConfirm: 'Arbeit verwerfen & re-provisionieren', // settings_repo_discard_confirm
  cancel: 'Abbrechen', // reuse generic cancel
  previewLoadFailed: 'Vorschau laden fehlgeschlagen', // load_failed (reuse)
  atRiskHeading: 'Betroffene Agenten-Worktrees:',
} as const

/** Concrete at-risk disclosure per agent worktree — names WHY it's at risk (uncommitted / unpushed), never generic.
 *  "Can't authorise a consequence you can't see" (CYP-461): when the backend names the work, show it (spec §3). */
export function atRiskLabel(a: AtRiskAgent): string {
  const reasons = [a.uncommitted ? 'nicht committet' : null, a.unpushed ? 'nicht gepusht' : null].filter(Boolean)
  return `${a.worktree} — ${reasons.length > 0 ? reasons.join(' + ') : 'unbekannt'}`
}
