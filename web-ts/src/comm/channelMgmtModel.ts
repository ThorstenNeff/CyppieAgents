// CYP-875 (OS-C, Epic CYP-867) — pure honesty model for channel management. The mutations are operator-tier and
// server-authoritative (render ≠ authority): these helpers are client-side HINTS (disable an affordance early), but the
// SERVER is the boundary — a HUB archive returns 409, a non-operator returns 403, and the UI surfaces those verbatim,
// never hiding them behind a pre-guess.
import { RestError } from '../net/rest'
import type { Channel, ChannelMemberGrant, CreateChannelRequest } from '../types/generated/contract'

/** The kinds a user may CREATE. HUB is the protected hub-and-spoke kind — never user-creatable (and not archivable). */
export const CREATABLE_KINDS = ['DIRECT', 'GROUP'] as const
export type CreatableKind = (typeof CREATABLE_KINDS)[number]

/**
 * A HUB channel is protected (hub-and-spoke) → NOT archivable. Client-side HINT to disable the affordance; the server
 * is authoritative (a HUB archive attempt returns 409, surfaced honestly). Fail-closed by construction: only a
 * non-HUB channel is archivable.
 */
export function isArchivable(channel: Channel): boolean {
  return channel.kind !== 'HUB'
}

/**
 * Membership IS the ACL (OS-B): a granted member reads AND writes (grant canRead||canWrite ⇒ member + AclEntry). The
 * create UI grants full read+write per selected agent; finer per-member ACL tuning stays in the AclPanel.
 */
export function memberGrant(agentId: string): ChannelMemberGrant {
  return { agentId, canRead: true, canWrite: true }
}

/**
 * Whether a create request is well-formed enough to SUBMIT (fail-closed: the submit affordance stays disabled until
 * valid): non-empty id + name, a CREATABLE kind (DIRECT/GROUP — never HUB), and at least one member grant. The server
 * still validates authoritatively; this only gates the client affordance.
 */
export function isCreateValid(req: Partial<CreateChannelRequest>): boolean {
  const kindOk = req.kind === 'DIRECT' || req.kind === 'GROUP'
  const idOk = typeof req.id === 'string' && req.id.trim().length > 0
  const nameOk = typeof req.name === 'string' && req.name.trim().length > 0
  const membersOk = Array.isArray(req.members) && req.members.length > 0
  return kindOk && idOk && nameOk && membersOk
}

/**
 * The HONEST, server-authoritative message for a failed mutation — mapped from the server's status, never hidden or
 * softened (render ≠ authority): 409 = a protected HUB channel (hub-and-spoke can't be archived), 403 = operator-only.
 * Anything else is an honest generic failure. The caller SHOWS this (and rolls back its optimistic change).
 */
export function channelMutationError(e: unknown): string {
  if (e instanceof RestError) {
    if (e.status === 409) return 'Kanal geschützt — Hub-and-Spoke-Kanäle können nicht archiviert werden.'
    if (e.status === 403) return 'Nur der Operator darf Kanäle verwalten.'
  }
  return 'Aktion fehlgeschlagen.'
}
