// CYP-407 (W9) — the ACL matrix honesty model, pure & tested (Spec §W9.2). The UI is a LIVE MIRROR of the
// enforced hub state; the cell reflects `enforced`, never the click. All the load-bearing honesty rules live here
// so the DOM component just renders them:
//   - non-optimistic: a switch's `checked` is the ENFORCED value; a pending change is a separate flag (the
//     AclEvent echo, not PUT 200, flips `checked`). aria-checked must use `checked`, never the optimistic click.
//   - conflict = deny-wins: multiple entries for one (channel,agent) fold to the strictest value.
//   - non-member = grantable, not inert: reported as member:false (the DOM draws a dashed "no member" marker, NOT
//     a disabled switch — a grant adds membership server-side, CYP-317).
//   - PO-lockout is ADVISORY: [isPoLockoutChange] flags a change that would remove the PO's read (→ confirm
//     dialog); the SERVER is the real guard (409 po_lockout_protected).
//   - preset is NON-ATOMIC: [presetDiff] yields the exact cell changes (preview "N cells", partial-progress),
//     never a silent "done".
import type { AclEntry } from '../types/generated/contract'

export type AclDimension = 'read' | 'write'

/** A pending change awaiting the AclEvent echo: keyed channel|agent|dimension → the requested value. */
export type PendingAcl = ReadonlyMap<string, boolean>

export const pendingKey = (channelId: string, agentId: string, dim: AclDimension): string => `${channelId}|${agentId}|${dim}`

/** Enforced value for a (channel,agent), folding any duplicate entries deny-wins. No entry → {false,false}. */
export function enforcedValue(entries: readonly AclEntry[], channelId: string, agentId: string): { canRead: boolean; canWrite: boolean } {
  const matches = entries.filter((e) => e.channelId === channelId && e.agentId === agentId)
  if (matches.length === 0) return { canRead: false, canWrite: false }
  // deny-wins: AND across duplicates (the strictest value stands).
  return {
    canRead: matches.every((e) => e.canRead),
    canWrite: matches.every((e) => e.canWrite),
  }
}

export interface SwitchState {
  /** the ENFORCED value — what aria-checked and the visual switch show (never the optimistic click). */
  checked: boolean
  /** a change is in flight for this dimension (aria-busy); checked stays until the AclEvent echo. */
  pending: boolean
}

export interface AclCellState {
  read: SwitchState
  write: SwitchState
  /** is this agent a member of the channel? false = grantable (dashed marker), NOT a disabled switch. */
  member: boolean
  /** the PO's own cell — turning read off here is the lockout-advisory case (confirm dialog). */
  poCritical: boolean
}

export function aclCellState(
  channelId: string,
  agentId: string,
  entries: readonly AclEntry[],
  pending: PendingAcl,
  channelMembers: readonly string[],
  poAgentId: string | null,
): AclCellState {
  const enforced = enforcedValue(entries, channelId, agentId)
  return {
    read: { checked: enforced.canRead, pending: pending.has(pendingKey(channelId, agentId, 'read')) },
    write: { checked: enforced.canWrite, pending: pending.has(pendingKey(channelId, agentId, 'write')) },
    member: channelMembers.includes(agentId),
    poCritical: poAgentId !== null && agentId === poAgentId,
  }
}

/** Advisory: would setting [dim]=[next] for this agent remove the PO's read → lockout risk (confirm dialog)? */
export function isPoLockoutChange(agentId: string, dim: AclDimension, next: boolean, poAgentId: string | null): boolean {
  return poAgentId !== null && agentId === poAgentId && dim === 'read' && next === false
}

/** A single cell change the preset would apply. */
export interface AclChange {
  channelId: string
  agentId: string
  canRead: boolean
  canWrite: boolean
}

/** Non-atomic preset: the exact set of cells that DIFFER between the enforced state and the target (for the
 *  "N cells change" preview + N/M progress). An empty list means nothing changes — never a silent "done". */
export function presetDiff(entries: readonly AclEntry[], target: readonly AclChange[]): AclChange[] {
  return target.filter((t) => {
    const cur = enforcedValue(entries, t.channelId, t.agentId)
    return cur.canRead !== t.canRead || cur.canWrite !== t.canWrite
  })
}
