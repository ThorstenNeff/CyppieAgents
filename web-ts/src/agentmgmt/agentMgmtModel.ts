// CYP-450 (P2-b) — Agenten-Verwaltung: the pure honesty core. The panel/dialogs draw these; the rules are here
// (tested). Two leitachsen (spec §0): irreversible = confirmation + consequences; the SERVER is authoritative —
// uniqueness/guardrail rejects (id collision, only-one-PO, last-PO) are server codes the UI SHOWS, never
// pre-guesses (a client "is the id free?" check would race-lie). Text ported 1:1 from the DE keys (CYP-86/87/88).
import { restErrorCode } from '../net/rest'
import type { WorktreeFate } from '../state/restRepo'

export type Role = 'PO' | 'WORKER' | 'PRODUCT_LEAD'

/** The role picker offers all three roles (contract enum PO/WORKER/PRODUCT_LEAD; keys agent_role_*). */
export const ROLE_OPTIONS: readonly Role[] = ['PO', 'WORKER', 'PRODUCT_LEAD']

export function roleLabel(role: Role): string {
  switch (role) {
    case 'PO':
      return 'PO' // agent_role_po
    case 'WORKER':
      return 'Worker' // agent_role_worker
    case 'PRODUCT_LEAD':
      return 'Product Lead' // agent_role_product_lead
  }
}

/** The destructive worktree choice defaults to KEEP — the non-destructive option is preselected (spec §3.3, tooth 3). */
export const DEFAULT_WORKTREE_FATE: WorktreeFate = 'keep'

/** The remove-confirm wording depends on the worktree fate: keep = "Agent entfernen", delete = "Endgültig löschen". */
export function removeConfirmLabel(fate: WorktreeFate): string {
  return fate === 'delete' ? 'Endgültig löschen' /* agent_remove_confirm_delete */ : 'Agent entfernen' /* agent_remove_confirm */
}

/** The data-loss warning (only shown for the destructive "delete" path). `%1$s` = the worktree name/path. */
export function worktreeDeleteWarning(worktree: string): string {
  // agent_remove_worktree_warning — may wrap, never ellipsis (spec §3.4 / §9).
  return `Nicht committete/nicht gepushte Arbeit in „${worktree}" geht unwiderbringlich verloren.`
}

/** CYP-86 rejects: the SERVER decides uniqueness/PO. `agent_exists` → id-collision; `po_already_exists` → 2nd PO;
 *  anything else (invalid_agent / network) → the generic create-failed. Never string-match the message. */
export function addRejectMessage(err: unknown): string {
  switch (restErrorCode(err)) {
    case 'agent_exists':
      return 'Diese Agent-ID existiert bereits' // agent_add_id_exists
    case 'po_already_exists':
      return 'Es gibt bereits einen PO – nur ein PO pro Projekt möglich.' // agent_add_po_exists
    default:
      return 'Anlegen fehlgeschlagen' // agent_add_error
  }
}

/** CYP-88 rejects — the CYP-101 split: `po_already_exists` (PO taken by ANOTHER) ≠ `last_po` (the only PO dropping
 *  the role). They map to DIFFERENT messages; collapsing both onto po_exists is the wrong message for a give-up. */
export function editRejectMessage(err: unknown): string {
  switch (restErrorCode(err)) {
    case 'po_already_exists':
      return 'Rolle PO ist belegt – nur ein PO pro Projekt.' // agent_edit_po_exists
    case 'last_po':
      return 'Der einzige PO kann die Rolle nicht abgeben – Hub-and-Spoke bräche.' // agent_edit_last_po
    default:
      return 'Speichern fehlgeschlagen' // agent_edit_error
  }
}

/** CYP-87 reject: the only PO is undeletable (server guard, advisory in the UI). Anything else → generic. */
export function removeRejectMessage(err: unknown): string {
  switch (restErrorCode(err)) {
    case 'last_po':
      return 'Der einzige PO kann nicht entfernt werden – Hub-and-Spoke bräche.' // agent_remove_last_po
    default:
      return 'Entfernen fehlgeschlagen' // agent_remove_error
  }
}

/** Static copy (ported DE keys), grouped so the components stay literal-free and the wording is testable. */
export const AGENT_MGMT_TEXT = {
  title: 'Agenten-Verwaltung', // agent_mgmt_title
  operatorRequired: 'Nur mit Operator-Token änderbar', // agent_mgmt_operator_required
  add: 'Agent hinzufügen', // agent_add
  edit: 'Bearbeiten', // agent_edit
  remove: 'Entfernen', // agent_remove
  save: 'Speichern', // agent_save
  cancel: 'Abbrechen', // agent_cancel
  addConfirm: 'Anlegen', // agent_add_confirm
  // create ≠ start — neutral/info, no success-green (spec §2). Points at the P2-a lifecycle controls.
  spawnHint: 'Angelegt. Der Agent startet noch nicht – über die Lifecycle-Steuerung starten.', // agent_add_spawn_hint
  removeTitle: (name: string) => `Agent „${name}" entfernen?`, // agent_remove_title
  removeConsequences: 'Die laufende Session wird gestoppt.', // agent_remove_consequences
  worktreeKeep: 'Worktree behalten', // agent_remove_worktree_keep
  worktreeDelete: 'Worktree löschen', // agent_remove_worktree_delete
  // amber "saved ≠ active" → restart (reuse agent_ctl_restart); NEVER success-green, no restart control here.
  editEffectHint:
    'Gespeichert. Wirkt erst beim nächsten Start des Agenten – jetzt neu starten, damit die neue Konfiguration zieht.', // agent_edit_effect_hint
  editIdLockedHint: 'ID und Worktree sind fest und hier nicht änderbar.', // agent_edit_id_locked_hint
  labelId: 'Agent-ID', // agent_add_id_label
  labelName: 'Name', // agent_add_name_label
  labelRole: 'Rolle', // agent_add_role_label
  labelPersona: 'Persona / CLAUDE.md', // agent_add_persona_label
  labelLaunch: 'Startkommando', // agent_add_launch_label
  labelWorktree: 'Worktree-Ordner', // agent_add_worktree_label
} as const
