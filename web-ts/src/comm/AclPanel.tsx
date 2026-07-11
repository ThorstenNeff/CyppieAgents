// CYP-425 (App-Assembly) — the ACL surface: the W9 AclMatrix plus the dialog wiring the matrix deliberately left
// to its parent (isPoLockoutChange / presetDiff). Two guarded actions:
//  - PO-lockout: toggling the PO's OWN read off is advisory — a confirm dialog first (the SERVER is the real guard,
//    409 po_lockout_protected; this just makes the operator confirm intent). Every other toggle commits directly.
//  - Hub-and-Spoke preset: previews presetDiff ("N Zellen ändern") before applying; the apply is NON-ATOMIC —
//    one PUT per differing cell — so it can never read as a silent "done".
// A commit is: mark the changed dims pending (aria-busy) + PUT; the ENFORCED switch flips only on the AclEvent
// echo (non-optimistic), which the store's applyAclEntry does. Read-only (non-operator) → matrix chips, no actions.
import { useState } from 'react'
import { AclMatrix } from './AclMatrix'
import { enforcedValue, isPoLockoutChange, presetDiff, type AclDimension, type AclChange, type PendingAcl } from './aclModel'
import { hubAndSpokeTarget } from './aclPreset'
import type { AclEntry, Channel } from '../types/generated/contract'

export interface AclPanelProps {
  channels: readonly Channel[]
  agents: readonly string[]
  entries: readonly AclEntry[]
  pending: PendingAcl
  poAgentId: string | null
  operator: boolean
  /** Commit one enforced cell: mark [dims] pending + PUT the entry. The parent owns pending + repo. */
  onCommit: (entry: AclEntry, dims: readonly AclDimension[]) => void
  /** transient reject notice (CYP-435) — a rejected PUT (e.g. 409 lockout) surfaces here instead of a stuck switch. */
  error?: string | null
}

interface LockoutPrompt {
  entry: AclEntry
  dim: AclDimension
}

const entryFor = (entries: readonly AclEntry[], channelId: string, agentId: string, dim: AclDimension, next: boolean): AclEntry => {
  const cur = enforcedValue(entries, channelId, agentId)
  return {
    channelId,
    agentId,
    canRead: dim === 'read' ? next : cur.canRead,
    canWrite: dim === 'write' ? next : cur.canWrite,
  }
}

const changedDims = (entries: readonly AclEntry[], change: AclChange): AclDimension[] => {
  const cur = enforcedValue(entries, change.channelId, change.agentId)
  const dims: AclDimension[] = []
  if (cur.canRead !== change.canRead) dims.push('read')
  if (cur.canWrite !== change.canWrite) dims.push('write')
  return dims
}

export function AclPanel({ channels, agents, entries, pending, poAgentId, operator, onCommit, error = null }: AclPanelProps) {
  const [lockout, setLockout] = useState<LockoutPrompt | null>(null)
  const [preset, setPreset] = useState<AclChange[] | null>(null)

  const channelsForMatrix = channels.map((c) => ({ id: c.id, members: c.members }))

  const handleToggle = (channelId: string, agentId: string, dim: AclDimension, next: boolean) => {
    const entry = entryFor(entries, channelId, agentId, dim, next)
    // Advisory PO-lockout: confirm before removing the PO's own read. Every other change commits directly.
    if (isPoLockoutChange(agentId, dim, next, poAgentId)) {
      setLockout({ entry, dim })
      return
    }
    onCommit(entry, [dim])
  }

  const openPreset = () => setPreset(presetDiff(entries, hubAndSpokeTarget(channels)))
  const applyPreset = () => {
    if (preset === null) return
    // Non-atomic: one PUT per differing cell.
    for (const change of preset) {
      const dims = changedDims(entries, change)
      if (dims.length > 0) onCommit({ channelId: change.channelId, agentId: change.agentId, canRead: change.canRead, canWrite: change.canWrite }, dims)
    }
    setPreset(null)
  }

  return (
    <div className="acl-panel" data-testid="acl-panel">
      {error !== null && (
        <p className="acl-error" role="alert" data-testid="acl-error">
          {error}
        </p>
      )}
      {operator && (
        <div className="acl-actions">
          <button type="button" className="acl-preset" data-testid="acl-preset-open" onClick={openPreset}>
            Hub-and-Spoke wiederherstellen
          </button>
        </div>
      )}

      <AclMatrix
        channels={channelsForMatrix}
        agents={agents}
        entries={entries}
        pending={pending}
        poAgentId={poAgentId}
        onToggle={handleToggle}
        readOnly={!operator}
      />

      {lockout !== null && (
        <div className="acl-dialog acl-lockout" role="alertdialog" aria-label="PO-Aussperrung bestätigen" data-testid="acl-lockout-dialog">
          <p>
            ⚠ Das entzieht dem PO (<strong>{lockout.entry.agentId}</strong>) die Leseberechtigung in Kanal{' '}
            <strong>{lockout.entry.channelId}</strong>. Der Hub kann das ablehnen. Trotzdem fortfahren?
          </p>
          <button type="button" className="acl-lockout-cancel" data-testid="acl-lockout-cancel" onClick={() => setLockout(null)}>
            Abbrechen
          </button>
          <button
            type="button"
            className="acl-lockout-confirm"
            data-testid="acl-lockout-confirm"
            onClick={() => {
              onCommit(lockout.entry, [lockout.dim])
              setLockout(null)
            }}
          >
            Trotzdem entziehen
          </button>
        </div>
      )}

      {preset !== null && (
        <div className="acl-dialog acl-preset-dialog" role="alertdialog" aria-label="Preset anwenden" data-testid="acl-preset-dialog">
          <p data-testid="acl-preset-count">
            {preset.length === 0
              ? 'Nichts zu ändern — die ACL entspricht bereits Hub-and-Spoke.'
              : `${preset.length} ${preset.length === 1 ? 'Zelle ändert' : 'Zellen ändern'} sich. Nacheinander anwenden?`}
          </p>
          <button type="button" className="acl-preset-cancel" data-testid="acl-preset-cancel" onClick={() => setPreset(null)}>
            Abbrechen
          </button>
          <button type="button" className="acl-preset-apply" data-testid="acl-preset-apply" disabled={preset.length === 0} onClick={applyPreset}>
            Anwenden
          </button>
        </div>
      )}
    </div>
  )
}
