// CYP-435 (fast-follow to CYP-425) — commit an ACL change with correct pending lifecycle. Non-optimistic: on
// SUCCESS the enforced flip + pending-clear arrive via the /ws/comm AclEvent echo (applyAclEntry). On REJECT the
// server sends NO echo (409 po_lockout_protected — exactly the guided lockout path — or any 4xx), so the pending
// MUST be cleared here or the switch stays aria-busy forever. Extracted from App so the reject path is unit-tested.
import type { HubRepo } from './restRepo'
import type { AclEntry } from '../types/generated/contract'
import type { AclDimension } from '../comm/aclModel'
import { RestError } from '../net/rest'

export interface AclCommitHooks {
  markPending: (channelId: string, agentId: string, dim: AclDimension, requested: boolean) => void
  clearPending: (channelId: string, agentId: string, dim: AclDimension) => void
  setError: (message: string | null) => void
}

/** A user-facing reason for a rejected PUT. 409 is the announced lockout guard; anything else is a generic failure. */
export function aclRejectMessage(err: unknown): string {
  if (err instanceof RestError && err.status === 409) return 'Vom Hub abgelehnt (PO-Aussperrschutz aktiv).'
  return 'ACL-Änderung fehlgeschlagen — bitte erneut versuchen.'
}

export async function commitAclChange(
  repo: HubRepo,
  hooks: AclCommitHooks,
  entry: AclEntry,
  dims: readonly AclDimension[],
): Promise<void> {
  hooks.setError(null)
  for (const dim of dims) hooks.markPending(entry.channelId, entry.agentId, dim, dim === 'read' ? entry.canRead : entry.canWrite)
  try {
    await repo.putAcl(entry)
    // success: DON'T clear pending here — the AclEvent echo flips the switch + clears pending (non-optimistic).
  } catch (err) {
    // rejected: no echo is coming → clear the spinner ourselves, and surface why (CYP-435).
    for (const dim of dims) hooks.clearPending(entry.channelId, entry.agentId, dim)
    hooks.setError(aclRejectMessage(err))
  }
}
