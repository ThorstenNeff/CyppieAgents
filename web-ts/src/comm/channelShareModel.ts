// CYP-659 (P11, Epic CYP-640) — the pure honesty core of Cross-Project Channel-Share, port of the CMP CrossProject*
// (CYP-93). Framework-free + unit-tested; the panel draws these. A channel is OWNED by one project (channel.projectId)
// and can be SHARED with other projects (the grantee set) so their agents can reach it. SET/REPLACE semantics: a PUT
// with the grantee set replaces it; an empty set (or self-only) revokes; a DELETE revokes.
//
// Honesty invariants (mirrors the CYP-651 projectModel drift-safe framing):
//  - The SERVER is authoritative for the gate: PUT/DELETE are `authenticatedApi(OPERATOR)` (structural; `operator_required`
//    on a non-operator). The client operator-gate is UX-ONLY and must replicate exactly that (NOT a per-owner check —
//    `channel.projectId` is display-only, the owning project, never a gate). GET is read-tier (the badge/status is not
//    a secret — a reader sees it).
//  - Non-optimistic: share-state is whatever the server ECHOES (ChannelShareView), never the local selection. Every
//    mutation (PUT/DELETE) returns the fresh view; the caller reflects that, never an optimistic flip.
//  - The "grantees must be from the known project list" rule is a CLIENT UX GUARD, NOT server-enforced (the store only
//    strips blank + the owner project). We still fail-closed on it: an unknown/self project id is never SENT.
//  - Revoke is a withdrawal (removes cross-project reach) → client-owned confirm (the server has no confirm token), and
//    all server codes are first-class.
import type { ChannelShareView, Project } from '../types/generated/contract'
import { restErrorCode } from '../net/rest'

/** The grantee candidates for a channel = the known projects MINUS the channel's own owning project (a channel can't
 *  be shared with itself; the server strips self too, but we never OFFER it). */
export function granteeCandidates(projects: readonly Project[], ownerProjectId: string | null | undefined): Project[] {
  return projects.filter((p) => p.id !== ownerProjectId)
}

/** The UX guard (server does NOT enforce this — computeShareRecord only strips blank + owner): keep only grantee ids
 *  that are in the known candidate set, de-duplicated. Fail-closed — an unknown or self id is DROPPED, never sent. */
export function sanitizeGrantees(selected: readonly string[], candidateIds: ReadonlySet<string>): string[] {
  return [...new Set(selected.filter((id) => candidateIds.has(id)))]
}

/** The projects a channel currently REACHES, derived from the server view's reachableScope (the closest the view gives
 *  to the raw sharedWith set — the reached agents' home projects). Drives which grantee boxes are pre-checked. */
export function currentGranteeIds(view: ChannelShareView | null): string[] {
  if (view === null || view.reachableScope === undefined) return []
  return [...new Set(view.reachableScope.map((a) => a.projectId))]
}

/** Shared iff the server view says so (never inferred from the local pick). */
export function isShared(view: ChannelShareView | null): boolean {
  return view?.shared === true
}

/** SET/REPLACE with an empty grantee set revokes (the store removes the record when the computed set is empty). So a
 *  "save" with nothing selected IS a revoke — the caller confirms it like an explicit revoke, never a silent wipe. */
export function isRevokingSet(sanitized: readonly string[]): boolean {
  return sanitized.length === 0
}

export const SHARE_OPERATOR_REQUIRED = 'operator_required'
export const SHARE_CHANNEL_NOT_FOUND = 'channel_not_found'

/** Map a share mutation reject to its curated message — keyed on the machine code, never the message string. */
export function shareMutationMessage(err: unknown): string {
  switch (restErrorCode(err)) {
    case SHARE_OPERATOR_REQUIRED:
      return 'Nur der Operator kann Kanäle für andere Projekte freigeben.'
    case SHARE_CHANNEL_NOT_FOUND:
      return 'Kanal nicht gefunden.'
    default:
      return 'Freigabe fehlgeschlagen.'
  }
}

export const CHANNEL_SHARE_TEXT = {
  title: 'Kanal-Freigaben',
  subtitle: 'Kanäle für andere Projekte freigeben (Cross-Project).',
  operatorRequired: 'Nur mit Operator-Token änderbar',
  ownerProjectLabel: (projectId: string) => `Besitzt von Projekt „${projectId}"`,
  sharedBadge: 'Geteilt',
  notSharedBadge: 'Nicht geteilt',
  sharedStatus: (count: number) => `Geteilt mit ${count} ${count === 1 ? 'Projekt' : 'Projekten'}`,
  notSharedStatus: 'Nicht geteilt.',
  sharedAt: (t: string) => `seit ${t}`,
  reachHeading: 'Erreichbare Agenten',
  reachEntry: (agentId: string, projectId: string, access: string) => `${agentId} · Projekt ${projectId} · ${access === 'WRITE' ? 'schreibend' : 'lesend'}`,
  granteeHeading: 'Für Projekte freigeben',
  noCandidates: 'Keine weiteren Projekte zum Freigeben vorhanden.',
  save: 'Freigabe speichern',
  revoke: 'Freigabe entziehen',
  // client-owned confirm (the server has no confirm token) — revoke withdraws all cross-project reach.
  revokeConfirm: 'Freigabe für alle Projekte entziehen? Fremde Agenten verlieren den Zugriff auf diesen Kanal.',
  revokeConfirmButton: 'Entziehen',
  saveRevokeConfirm: 'Kein Projekt ausgewählt – das entzieht die Freigabe. Fortfahren?',
  cancel: 'Abbrechen',
  loadError: 'Freigabe-Status konnte nicht geladen werden.',
  retry: 'Erneut laden',
} as const

export const CHANNEL_SHARE_TESTID = {
  panel: 'channelShare.panel',
  gate: 'channelShare.gateHint',
  empty: 'channelShare.empty',
  row: (channelId: string) => `channelShare.row.${channelId}`,
  badge: (channelId: string) => `channelShare.badge.${channelId}`,
  status: (channelId: string) => `channelShare.status.${channelId}`,
  ownerProject: (channelId: string) => `channelShare.owner.${channelId}`,
  reach: (channelId: string) => `channelShare.reach.${channelId}`,
  reachEntry: (channelId: string, agentId: string) => `channelShare.reach.${channelId}.${agentId}`,
  grantee: (channelId: string, projectId: string) => `channelShare.grantee.${channelId}.${projectId}`,
  save: (channelId: string) => `channelShare.save.${channelId}`,
  revoke: (channelId: string) => `channelShare.revoke.${channelId}`,
  confirm: (channelId: string) => `channelShare.confirm.${channelId}`,
  confirmOk: (channelId: string) => `channelShare.confirm.${channelId}.ok`,
  confirmCancel: (channelId: string) => `channelShare.confirm.${channelId}.cancel`,
  error: (channelId: string) => `channelShare.error.${channelId}`,
  loadError: (channelId: string) => `channelShare.loadError.${channelId}`,
} as const
