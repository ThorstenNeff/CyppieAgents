// CYP-453 (P2-f) — the Settings-level honesty helpers for the Repo-Section (the API-key section's leak model lives in
// CYP-433, not here). Text ported 1:1 from the DE keys (project-settings-keys, 0 new). The repo save reject is
// server-authoritative (invalid_repo_url) — mapped from the RestError code, never a client string-match.
import { restErrorCode } from '../net/rest'

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
} as const
