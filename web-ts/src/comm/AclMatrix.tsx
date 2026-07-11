// CYP-407 (W9) — the ACL matrix as a native <table> (Spec §W9.2: scope headers announce row/col for free). Each
// cell has read + write switches whose aria-checked is the ENFORCED value (never the optimistic click) and
// aria-busy the pending flag — the a11y honesty the model (aclModel.ts) computes. A NON-member cell renders a
// "kein Mitglied" marker, NOT a disabled switch (grantable, CYP-317). Read-only (non-operator) → chips, no switches.
// The PO-lockout / self-blind / preset dialogs are the parent's wiring (it uses isPoLockoutChange/presetDiff).
import { aclCellState, type AclDimension, type PendingAcl } from './aclModel'
import type { AclEntry } from '../types/generated/contract'

export interface AclMatrixProps {
  channels: readonly { id: string; members: readonly string[] }[]
  agents: readonly string[]
  entries: readonly AclEntry[]
  pending: PendingAcl
  poAgentId: string | null
  onToggle: (channelId: string, agentId: string, dim: AclDimension, next: boolean) => void
  readOnly?: boolean
}

const dimLabel = (dim: AclDimension): string => (dim === 'read' ? 'Lesen' : 'Antworten')

export function AclMatrix({ channels, agents, entries, pending, poAgentId, onToggle, readOnly = false }: AclMatrixProps) {
  return (
    <table className="acl-matrix" data-testid="acl-matrix">
      <thead>
        <tr>
          <th scope="col" />
          {agents.map((a) => (
            <th key={a} scope="col" data-testid={`acl.col.${a}`}>
              {a}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {channels.map((ch) => (
          <tr key={ch.id}>
            <th scope="row" data-testid={`acl.row.${ch.id}`}>
              {ch.id}
            </th>
            {agents.map((a) => {
              const cell = aclCellState(ch.id, a, entries, pending, ch.members, poAgentId)
              return (
                <td key={a} className={cell.member ? 'acl-cell' : 'acl-cell non-member'} data-testid={`acl.cell.${ch.id}.${a}`}>
                  {!cell.member ? (
                    <span className="acl-non-member" data-testid={`acl.cell.${ch.id}.${a}.nonMember`}>
                      kein Mitglied
                    </span>
                  ) : (
                    (['read', 'write'] as const).map((dim) => {
                      const s = cell[dim]
                      const tid = `acl.cell.${ch.id}.${a}.${dim}`
                      return readOnly ? (
                        <span key={dim} className="acl-readonly" data-testid={tid}>
                          {dimLabel(dim)}: {s.checked ? 'erlaubt' : '—'}
                        </span>
                      ) : (
                        <button
                          key={dim}
                          type="button"
                          role="switch"
                          aria-checked={s.checked}
                          aria-busy={s.pending}
                          aria-label={dimLabel(dim)}
                          className="acl-switch"
                          data-testid={tid}
                          onClick={() => onToggle(ch.id, a, dim, !s.checked)}
                        >
                          {dimLabel(dim)}
                        </button>
                      )
                    })
                  )}
                </td>
              )
            })}
          </tr>
        ))}
      </tbody>
    </table>
  )
}
