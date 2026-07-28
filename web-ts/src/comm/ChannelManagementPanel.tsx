// CYP-875 (OS-C) — the operator channel-management panel: create (DIRECT/GROUP), rename, archive. Mutations are
// OPTIMISTIC with rollback-on-reject, and the server stays the authority (render ≠ authority): a 409 (protected HUB) or
// 403 (operator-only) is shown HONESTLY (never a silent success, never hidden), and the optimistic change is rolled
// back so no phantom hangs. HUB channels are protected — the archive affordance is disabled (client hint), and the
// server 409 is the authoritative guard.
import { useState } from 'react'
import {
  CREATABLE_KINDS,
  isArchivable,
  memberGrant,
  isCreateValid,
  channelMutationError,
  type CreatableKind,
} from './channelMgmtModel'
import type { Channel, CreateChannelRequest } from '../types/generated/contract'

export function ChannelManagementPanel({
  channels,
  agentIds,
  operator,
  onCreateChannel,
  onRenameChannel,
  onArchiveChannel,
}: {
  channels: readonly Channel[]
  /** Candidate members for a new channel (the roster agent ids). */
  agentIds: readonly string[]
  /** Operator tier — mutations are operator-only. A non-operator sees them disabled; a server 403 is still shown honestly. */
  operator: boolean
  onCreateChannel: (req: CreateChannelRequest) => Promise<Channel>
  onRenameChannel: (channelId: string, name: string) => Promise<Channel>
  onArchiveChannel: (channelId: string) => Promise<void>
}) {
  const [error, setError] = useState<string | null>(null)
  const [optimisticArchived, setOptimisticArchived] = useState<ReadonlySet<string>>(new Set())
  const [optimisticCreated, setOptimisticCreated] = useState<readonly Channel[]>([])
  const [renameDraft, setRenameDraft] = useState<Record<string, string>>({})
  // create form
  const [newId, setNewId] = useState('')
  const [newName, setNewName] = useState('')
  const [newKind, setNewKind] = useState<CreatableKind>('GROUP')
  const [newMembers, setNewMembers] = useState<readonly string[]>([])

  // The visible set = server channels ∪ pending optimistic creates (deduped by id once the real one arrives via
  // /ws/comm), minus optimistically-archived ones.
  const visible = [...channels, ...optimisticCreated.filter((o) => !channels.some((c) => c.id === o.id))].filter(
    (c) => !optimisticArchived.has(c.id),
  )

  const createReq = (): CreateChannelRequest => ({ id: newId.trim(), name: newName.trim(), kind: newKind, members: newMembers.map(memberGrant) })

  const create = () => {
    const req = createReq()
    if (!isCreateValid(req)) return
    setError(null)
    const optimistic: Channel = { id: req.id, name: req.name, kind: newKind, members: [...newMembers] }
    setOptimisticCreated((prev) => [...prev, optimistic]) // OPTIMISTIC add
    setNewId('')
    setNewName('')
    setNewMembers([])
    void onCreateChannel(req).catch((e: unknown) => {
      setOptimisticCreated((prev) => prev.filter((c) => c.id !== optimistic.id)) // ROLLBACK
      setError(channelMutationError(e)) // HONEST, server-authoritative
    })
  }

  const archive = (c: Channel) => {
    setError(null)
    setOptimisticArchived((prev) => new Set(prev).add(c.id)) // OPTIMISTIC hide
    void onArchiveChannel(c.id).catch((e: unknown) => {
      setOptimisticArchived((prev) => {
        const next = new Set(prev)
        next.delete(c.id)
        return next
      }) // ROLLBACK — the channel reappears, no phantom removal
      setError(channelMutationError(e)) // HONEST (409 protected-HUB / 403 operator)
    })
  }

  const rename = (c: Channel) => {
    const name = (renameDraft[c.id] ?? '').trim()
    if (name.length === 0 || name === c.name) return
    setError(null)
    void onRenameChannel(c.id, name).catch((e: unknown) => {
      setError(channelMutationError(e)) // HONEST; the name reverts (draft cleared, prop is source of truth)
      setRenameDraft((prev) => ({ ...prev, [c.id]: '' }))
    })
  }

  const canCreate = operator && isCreateValid(createReq())

  return (
    <section className="channel-mgmt" data-testid="channel-mgmt" aria-label="Kanäle verwalten">
      {error !== null && (
        <p className="channel-mgmt-error" role="alert" data-testid="channel-mgmt.error">
          {error}
        </p>
      )}

      {/* CREATE */}
      <form
        className="channel-mgmt-create"
        data-testid="channel-mgmt.create"
        onSubmit={(e) => {
          e.preventDefault()
          create()
        }}
      >
        <input aria-label="Kanal-ID" data-testid="channel-mgmt.create.id" value={newId} disabled={!operator} onChange={(e) => setNewId(e.target.value)} />
        <input aria-label="Kanal-Name" data-testid="channel-mgmt.create.name" value={newName} disabled={!operator} onChange={(e) => setNewName(e.target.value)} />
        <select aria-label="Kanal-Typ" data-testid="channel-mgmt.create.kind" value={newKind} disabled={!operator} onChange={(e) => setNewKind(e.target.value as CreatableKind)}>
          {CREATABLE_KINDS.map((k) => (
            <option key={k} value={k}>
              {k}
            </option>
          ))}
        </select>
        <fieldset className="channel-mgmt-members" data-testid="channel-mgmt.create.members">
          {agentIds.map((a) => (
            <label key={a}>
              <input
                type="checkbox"
                data-testid={`channel-mgmt.create.member.${a}`}
                checked={newMembers.includes(a)}
                disabled={!operator}
                onChange={(e) => setNewMembers((prev) => (e.target.checked ? [...prev, a] : prev.filter((x) => x !== a)))}
              />
              {a}
            </label>
          ))}
        </fieldset>
        <button type="submit" data-testid="channel-mgmt.create.submit" disabled={!canCreate}>
          Kanal anlegen
        </button>
      </form>

      {/* MANAGE existing */}
      <ul className="channel-mgmt-list">
        {visible.map((c) => (
          <li key={c.id} className="channel-mgmt-row" data-testid={`channel-mgmt.row.${c.id}`}>
            <span className="channel-mgmt-name">
              {c.name} <span className="channel-mgmt-kind">({c.kind})</span>
            </span>
            <input
              aria-label={`${c.name} umbenennen`}
              data-testid={`channel-mgmt.rename.${c.id}`}
              value={renameDraft[c.id] ?? ''}
              disabled={!operator}
              onChange={(e) => setRenameDraft((prev) => ({ ...prev, [c.id]: e.target.value }))}
            />
            <button type="button" data-testid={`channel-mgmt.rename.submit.${c.id}`} disabled={!operator} onClick={() => rename(c)}>
              Umbenennen
            </button>
            {/* HUB is protected → archive disabled (client hint); the server 409 is the authoritative guard. */}
            <button
              type="button"
              data-testid={`channel-mgmt.archive.${c.id}`}
              disabled={!operator || !isArchivable(c)}
              onClick={() => archive(c)}
            >
              Archivieren
            </button>
          </li>
        ))}
      </ul>
    </section>
  )
}
