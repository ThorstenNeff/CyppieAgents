// CYP-651 (P8, Epic CYP-640) — the pure project switch/management policy. Parity with CMP ProjectManagementPanel
// (CYP-91/S13) + Backend2's server-truth spike. Framework-free + unit-tested.
//
// DRIFT-SAFETY (Backend2): the `:core ProjectGuard` is the authoritative correctness boundary (fail-closed, runs
// before any parse). web-ts has NO compiler link, so this replicates the rules FOR UX ONLY — disabling the delete
// on the active/last project, arming the name-echo, preempting a doomed call. A client drift can only show a stale
// hint, NEVER let a wrong mutation through: the caller MUST still treat all 3 server codes (404 / 409-last /
// 409-active) as first-class responses regardless of what this pre-check thought.

/** Why a project's delete is blocked (UX pre-check). Order mirrors the server guard: `last` before `active` — a lone
 *  project is BOTH, and the server reports last_project first, so we match. `null` = deletable (subject to name-echo). */
export type DeleteBlockedReason = 'last' | 'active'
export function deleteBlockedReason(projectId: string, activeProjectId: string, projectCount: number): DeleteBlockedReason | null {
  if (projectCount <= 1) return 'last' // the last project is undeletable (409 last_project)
  if (projectId === activeProjectId) return 'active' // the active project must be switched away FIRST (409 active_project_protected)
  return null
}

/** The name-echo confirm (CLIENT UX guard — there is NO server confirm token). The typed value must EXACTLY match the
 *  project name (trimmed) to arm the hard delete. */
export function nameEchoMatches(typed: string, projectName: string): boolean {
  return typed.trim() === projectName
}

/** The destructive-delete GATE: the DELETE may fire ONLY when the project is not blocked (not active, not last) AND
 *  the name-echo matches. This is the load-bearing client tooth — a delete-without-confirm must never call. (The
 *  server ProjectGuard fail-closes independently; this prevents the doomed/dangerous call from the UI at all.) */
export function canFireProjectDelete(input: {
  projectId: string
  activeProjectId: string
  projectCount: number
  nameEcho: string
  projectName: string
}): boolean {
  return (
    deleteBlockedReason(input.projectId, input.activeProjectId, input.projectCount) === null &&
    nameEchoMatches(input.nameEcho, input.projectName)
  )
}

/** A switch is a safe no-op when the target is already active (the server 404-guards only). Lets the UI skip a
 *  heavyweight, session-draining POST for a click on the current project. */
export function switchIsNoop(targetProjectId: string, activeProjectId: string): boolean {
  return targetProjectId === activeProjectId
}

/** Map a server project-mutation error code to a user-facing German message. A 404 on delete is 'already gone' →
 *  benign (the list just refetches). Unknown codes fall to a generic failure (never a fabricated success). */
export function projectMutationMessage(code: string | null): string {
  switch (code) {
    case 'project_not_found':
      return 'Projekt nicht gefunden (evtl. schon gelöscht).'
    case 'last_project':
      return 'Das letzte Projekt kann nicht gelöscht werden.'
    case 'active_project_protected':
      return 'Das aktive Projekt kann nicht gelöscht werden — erst wechseln.'
    case 'project_exists':
      return 'Eine Projekt-ID mit diesem Wert existiert bereits.'
    case 'invalid_project_id':
      return 'Ungültige Projekt-ID.'
    default:
      return 'Aktion fehlgeschlagen.'
  }
}
