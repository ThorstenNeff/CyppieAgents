// CYP-450 (P2-b) — Agenten-Verwaltung: add / remove / edit, ported from Compose AgentManagementPanel. Two leitachsen
// (spec §0): (1) irreversible = confirmation + consequences (remove = alertdialog, worktree default KEEP + data-loss
// warning); (2) the SERVER is authoritative — uniqueness/PO guardrails (agent_exists / po_already_exists / last_po)
// are server rejects the UI SHOWS on the dialog's error line, never pre-guesses (a client "id free?" check race-lies).
// Non-optimistic: the list flips only after the server confirms (App refetches the roster); a reject keeps the dialog
// open with its error. Operator-gate is present-but-disabled (aria-disabled), never a fake and never omission — the
// list stays visible (display is ungated; mutation is gated). create ≠ start (spawnHint → P2-a); edit takes effect on
// restart (amber effectHint → P2-a restartBtn, no restart control here); id + worktree are locked in edit.
import { useEffect, useState } from 'react'
import type { Agent, AgentEdit, NewAgentSpec, ConnectorsView } from '../types/generated/contract'
import type { AgentRunState } from '../state/hubReducers'
import type { WorktreeFate } from '../state/restRepo'
import { ConnectorPicker } from '../connector/ConnectorPicker'
import type { ConnectorKind } from '../connector/connectorModel'
import { statusDotSpec, dotRoleVar, lifecycleLabel } from '../agentview/lifecycleStatus'
import { LoadErrorRetry } from '../ui/LoadErrorRetry'
import {
  ROLE_OPTIONS,
  roleLabel,
  DEFAULT_WORKTREE_FATE,
  removeConfirmLabel,
  worktreeDeleteWarning,
  addRejectMessage,
  editRejectMessage,
  removeRejectMessage,
  AGENT_MGMT_TEXT as T,
  type Role,
} from './agentMgmtModel'

export interface AgentManagementPanelProps {
  agents: readonly Agent[]
  operator: boolean
  // CYP-288: a failed INITIAL roster load (GET /api/agents) surfaces error+retry instead of an empty list (failed ≠
  // empty). Gated on the roster still being empty → a live/WS roster update or a successful retry hides the error.
  loadError?: boolean
  onRetryLoad?: () => void
  runStateByAgent: ReadonlyMap<string, AgentRunState>
  /** CYP-741: per-agent busy (CYP-641 feed). Absent ⇒ NOT busy — a missing value is never a guessed "working". */
  busyByAgent?: ReadonlyMap<string, boolean>
  /** All three resolve on server-confirm and reject (RestError) on a server reject. The App does the roster refetch
   *  on success (non-optimistic); the dialogs surface the mapped reject on failure. */
  onCreate: (spec: NewAgentSpec) => Promise<void>
  onUpdate: (id: string, edit: AgentEdit) => Promise<void>
  onRemove: (id: string, fate: WorktreeFate) => Promise<void>
  /** For the edit dialog to PREFILL current persona/launch (not on the roster Agent). */
  fetchDetail: (id: string) => Promise<{ role: Role; persona?: string | null; launch?: string | null }>
  /** CYP-461: the advisory connector preview source (GET /api/connectors) — passed to the picker. */
  getConnectors: () => Promise<ConnectorsView>
  /** CYP-461 (edit): commit a connector change (POST /api/agents/{id}/connector) — the ONLY path that changes it. */
  onSetConnector: (id: string, kind: ConnectorKind) => Promise<void>
}

type Dialog = null | { kind: 'add' } | { kind: 'edit'; agent: Agent } | { kind: 'remove'; agent: Agent }
type Notice = null | { kind: 'spawn' } | { kind: 'effect' }

export function AgentManagementPanel({
  agents,
  operator,
  loadError = false,
  onRetryLoad,
  runStateByAgent,
  busyByAgent,
  onCreate,
  onUpdate,
  onRemove,
  fetchDetail,
  getConnectors,
  onSetConnector,
}: AgentManagementPanelProps) {
  const [dialog, setDialog] = useState<Dialog>(null)
  const [notice, setNotice] = useState<Notice>(null)

  return (
    <div className="agent-mgmt" data-testid="agentMgmt.panel">
      <div className="agent-mgmt-header">
        <h2>{T.title}</h2>
        <button
          type="button"
          data-testid="agentMgmt.addButton"
          disabled={!operator}
          aria-disabled={!operator}
          onClick={() => {
            setNotice(null)
            setDialog({ kind: 'add' })
          }}
        >
          {T.add}
        </button>
      </div>

      {!operator && (
        <p className="agent-mgmt-gate" role="note" data-testid="agentMgmt.gateHint">
          {T.operatorRequired}
        </p>
      )}

      {notice?.kind === 'spawn' && (
        <p className="agent-mgmt-spawn-hint" role="status" data-testid="agentMgmt.add.spawnHint">
          {T.spawnHint}
        </p>
      )}
      {notice?.kind === 'effect' && (
        <p className="agent-mgmt-effect-hint" role="status" data-testid="agentMgmt.edit.effectHint">
          {T.editEffectHint}
        </p>
      )}

      {agents.length === 0 && loadError ? (
        // CYP-288: a failed roster load shows error+retry, not an empty list (which reads as "no agents"). A
        // genuinely-empty roster (no error) still renders the empty list — non-vacuum contrast.
        <LoadErrorRetry testId="agentMgmt.loadError" onRetry={onRetryLoad ?? (() => undefined)} />
      ) : (
      <ul className="agent-mgmt-list" data-testid="agentMgmt.list">
        {agents.map((a) => (
          <li key={a.id} className="agent-mgmt-item" data-testid={`agentMgmt.item.${a.id}`}>
            {/* CYP-741 — the SAME dot as the LifecycleHeader (statusDotSpec/dotRoleVar, CYP-431): one fact, one
                source, two places. The honesty core is UNKNOWN: an agent whose run-state we have never observed
                gets a RING, not STOPPED's filled disc — unobserved is a different axis from off, and painting it
                grey would claim we looked. The text label STAYS (colour is never the sole carrier); the dot only
                reinforces it, which is why the dot is aria-hidden. */}
            {(() => {
              const state = runStateByAgent.get(a.id) ?? 'UNKNOWN'
              const spec = statusDotSpec(state, false)
              const color = dotRoleVar(spec.role)
              return (
                <span
                  className="agent-mgmt-dot"
                  aria-hidden="true"
                  data-testid={`agentMgmt.item.${a.id}.dot`}
                  data-shape={spec.shape}
                  data-role={spec.role}
                  style={
                    spec.shape === 'fill'
                      ? { width: 8, height: 8, borderRadius: '50%', background: color }
                      : { width: 8, height: 8, borderRadius: '50%', border: `2px solid ${color}`, boxSizing: 'border-box' }
                  }
                />
              )
            })()}
            <span className="agent-mgmt-status" data-testid={`agentMgmt.item.${a.id}.status`}>
              {lifecycleLabel(runStateByAgent.get(a.id) ?? 'UNKNOWN', undefined)}
            </span>
            {/* Busy is a DISTINCT fact from run-state (a RUNNING agent can be idle), so it gets its own marker —
                never folded into the dot. Absent ⇒ not busy (fail-closed), and suppressed under ERROR: "working"
                next to an error is a mixed signal (same rule as deriveWindowActivity). */}
            {(busyByAgent?.get(a.id) ?? false) && (runStateByAgent.get(a.id) ?? 'UNKNOWN') !== 'ERROR' && (
              <span className="agent-mgmt-busy" data-testid={`agentMgmt.item.${a.id}.busy`} aria-label="arbeitet">
                ●
              </span>
            )}
            <span className="agent-mgmt-name">{a.name}</span>
            <span className="agent-mgmt-role">{roleLabel(a.role as Role)}</span>
            <button
              type="button"
              data-testid={`agentMgmt.item.${a.id}.edit`}
              disabled={!operator}
              aria-disabled={!operator}
              onClick={() => {
                setNotice(null)
                setDialog({ kind: 'edit', agent: a })
              }}
            >
              {T.edit}
            </button>
            <button
              type="button"
              className="agent-mgmt-remove"
              data-testid={`agentMgmt.item.${a.id}.remove`}
              disabled={!operator}
              aria-disabled={!operator}
              onClick={() => {
                setNotice(null)
                setDialog({ kind: 'remove', agent: a })
              }}
            >
              {T.remove}
            </button>
          </li>
        ))}
      </ul>
      )}

      {dialog?.kind === 'add' && (
        <AddDialog
          onCancel={() => setDialog(null)}
          onCreate={onCreate}
          getConnectors={getConnectors}
          onDone={() => {
            setDialog(null)
            setNotice({ kind: 'spawn' }) // create ≠ start (spec §2)
          }}
        />
      )}
      {dialog?.kind === 'edit' && (
        <EditDialog
          agent={dialog.agent}
          fetchDetail={fetchDetail}
          onCancel={() => setDialog(null)}
          onUpdate={onUpdate}
          getConnectors={getConnectors}
          onSetConnector={onSetConnector}
          onDone={() => {
            setDialog(null)
            setNotice({ kind: 'effect' }) // saved ≠ active — restart (spec §4)
          }}
        />
      )}
      {dialog?.kind === 'remove' && (
        <RemoveDialog
          agent={dialog.agent}
          onCancel={() => setDialog(null)}
          onRemove={onRemove}
          onDone={() => setDialog(null)}
        />
      )}
    </div>
  )
}

function RoleRadioGroup({ testid, value, onChange }: { testid: string; value: Role; onChange: (r: Role) => void }) {
  return (
    <div role="radiogroup" aria-label={T.labelRole} data-testid={testid} className="agent-mgmt-roles">
      {ROLE_OPTIONS.map((r) => (
        <label key={r}>
          <input type="radio" checked={value === r} onChange={() => onChange(r)} data-testid={`${testid}.${r}`} />
          {roleLabel(r)}
        </label>
      ))}
    </div>
  )
}

function AddDialog({
  onCancel,
  onCreate,
  getConnectors,
  onDone,
}: {
  onCancel: () => void
  onCreate: (spec: NewAgentSpec) => Promise<void>
  getConnectors: () => Promise<ConnectorsView>
  onDone: () => void
}) {
  const [id, setId] = useState('')
  const [name, setName] = useState('')
  const [role, setRole] = useState<Role>('WORKER')
  const [persona, setPersona] = useState('')
  const [launch, setLaunch] = useState('')
  const [worktree, setWorktree] = useState('')
  // CYP-461: A (stream_json) is the first-class default; the kind rides NewAgentSpec.connectorKind on the create
  // (no /connector endpoint call for a fresh spawn). B still passes through the ack-gated opt-in in the picker.
  const [connectorKind, setConnectorKind] = useState<ConnectorKind>('stream_json')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    setError(null)
    setBusy(true)
    try {
      // Optional fields go up only when non-empty (blank ≠ an intentional null). Uniqueness is the SERVER's call.
      await onCreate({
        id: id.trim(),
        name: name.trim(),
        role,
        persona: persona.trim() || null,
        launch: launch.trim() || null,
        worktree: worktree.trim() || null,
        connectorKind,
      })
      onDone()
    } catch (e) {
      setError(addRejectMessage(e)) // server-authoritative reject (agent_exists / po_already_exists / …)
      setBusy(false)
    }
  }

  return (
    <div role="dialog" aria-label={T.add} className="agent-mgmt-dialog" data-testid="agentMgmt.add.dialog">
      <label>
        {T.labelId}
        <input data-testid="agentMgmt.add.id.input" value={id} onChange={(e) => setId(e.target.value)} />
      </label>
      <label>
        {T.labelName}
        <input data-testid="agentMgmt.add.name.input" value={name} onChange={(e) => setName(e.target.value)} />
      </label>
      <RoleRadioGroup testid="agentMgmt.add.role.picker" value={role} onChange={setRole} />
      <label>
        {T.labelPersona}
        <textarea
          data-testid="agentMgmt.add.persona.input"
          value={persona}
          onChange={(e) => setPersona(e.target.value)}
        />
      </label>
      <label>
        {T.labelLaunch}
        <input data-testid="agentMgmt.add.launch.input" value={launch} onChange={(e) => setLaunch(e.target.value)} />
      </label>
      <label>
        {T.labelWorktree}
        <input
          data-testid="agentMgmt.add.worktree.input"
          value={worktree}
          onChange={(e) => setWorktree(e.target.value)}
        />
      </label>
      {/* CYP-461: connector picker — add carries the kind on the spec; B goes through the ack-gated opt-in but here
          onConfirm just settles the draft (no /connector call for a fresh spawn, §5). Dialog is operator-gated → editable. */}
      <ConnectorPicker
        kind={connectorKind}
        initialKind="stream_json"
        mode="add"
        editable
        getConnectors={getConnectors}
        onConfirm={(k) => {
          setConnectorKind(k)
          return Promise.resolve()
        }}
      />
      {error !== null && (
        <p className="agent-mgmt-error" role="alert" data-testid="agentMgmt.add.error">
          {error}
        </p>
      )}
      <div className="agent-mgmt-actions">
        <button type="button" data-testid="agentMgmt.add.cancel" onClick={onCancel}>
          {T.cancel}
        </button>
        <button type="button" data-testid="agentMgmt.add.confirm" onClick={submit} disabled={busy}>
          {T.addConfirm}
        </button>
      </div>
    </div>
  )
}

function EditDialog({
  agent,
  fetchDetail,
  onCancel,
  onUpdate,
  getConnectors,
  onSetConnector,
  onDone,
}: {
  agent: Agent
  fetchDetail: (id: string) => Promise<{ role: Role; persona?: string | null; launch?: string | null }>
  onCancel: () => void
  onUpdate: (id: string, edit: AgentEdit) => Promise<void>
  getConnectors: () => Promise<ConnectorsView>
  onSetConnector: (id: string, kind: ConnectorKind) => Promise<void>
  onDone: () => void
}) {
  const [role, setRole] = useState<Role>(agent.role as Role)
  const [persona, setPersona] = useState('')
  const [launch, setLaunch] = useState('')
  // CYP-461: the server-side kind is the truth (initialKind); the draft settles via the picker → POST /connector.
  const initialKind = (agent.connectorKind ?? 'stream_json') as ConnectorKind
  const [connectorKind, setConnectorKind] = useState<ConnectorKind>(initialKind)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // Prefill current persona/launch (not on the roster Agent) so a save never blanks them out.
  useEffect(() => {
    let live = true
    fetchDetail(agent.id)
      .then((d) => {
        if (!live) return
        setRole(d.role)
        setPersona(d.persona ?? '')
        setLaunch(d.launch ?? '')
      })
      .catch(() => undefined)
    return () => {
      live = false
    }
  }, [agent.id, fetchDetail])

  const submit = async () => {
    setError(null)
    setBusy(true)
    try {
      await onUpdate(agent.id, { role, persona: persona.trim() || null, launch: launch.trim() || null })
      onDone()
    } catch (e) {
      // CYP-101: po_already_exists (taken) ≠ last_po (give-up) — the mapper keeps them distinct.
      setError(editRejectMessage(e))
      setBusy(false)
    }
  }

  return (
    <div role="dialog" aria-label={T.edit} className="agent-mgmt-dialog" data-testid="agentMgmt.edit.dialog">
      {/* id + worktree are FIXED — shown read-only, never editable (spec §4, tooth 6). */}
      <p className="agent-mgmt-locked" data-testid="agentMgmt.edit.idLockedHint">
        {T.editIdLockedHint}
      </p>
      <p className="agent-mgmt-locked-vals">
        {agent.id} · {agent.worktree}
      </p>
      <RoleRadioGroup testid="agentMgmt.edit.role.picker" value={role} onChange={setRole} />
      <label>
        {T.labelPersona}
        <textarea
          data-testid="agentMgmt.edit.persona.input"
          value={persona}
          onChange={(e) => setPersona(e.target.value)}
        />
      </label>
      <label>
        {T.labelLaunch}
        <input data-testid="agentMgmt.edit.launch.input" value={launch} onChange={(e) => setLaunch(e.target.value)} />
      </label>
      {/* CYP-461: connector picker — EDIT commits a change via POST /connector (the ONLY connector-change path, §5/§6),
          separate from the role/persona save (connectorKind is NOT in AgentEdit). A settles immediately; B is ack-gated. */}
      <ConnectorPicker
        kind={connectorKind}
        initialKind={initialKind}
        mode="edit"
        editable
        getConnectors={getConnectors}
        onConfirm={(k) => onSetConnector(agent.id, k).then(() => setConnectorKind(k))}
      />
      {error !== null && (
        <p className="agent-mgmt-error" role="alert" data-testid="agentMgmt.edit.error">
          {error}
        </p>
      )}
      <div className="agent-mgmt-actions">
        <button type="button" data-testid="agentMgmt.edit.cancel" onClick={onCancel}>
          {T.cancel}
        </button>
        <button type="button" data-testid="agentMgmt.edit.save" onClick={submit} disabled={busy}>
          {T.save}
        </button>
      </div>
    </div>
  )
}

function RemoveDialog({
  agent,
  onCancel,
  onRemove,
  onDone,
}: {
  agent: Agent
  onCancel: () => void
  onRemove: (id: string, fate: WorktreeFate) => Promise<void>
  onDone: () => void
}) {
  const [fate, setFate] = useState<WorktreeFate>(DEFAULT_WORKTREE_FATE) // default KEEP (non-destructive)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    setError(null)
    setBusy(true)
    try {
      await onRemove(agent.id, fate)
      onDone()
    } catch (e) {
      setError(removeRejectMessage(e)) // last_po is the server guard; advisory here
      setBusy(false)
    }
  }

  return (
    <div
      role="alertdialog"
      aria-label={T.removeTitle(agent.name)}
      className="agent-mgmt-dialog agent-mgmt-remove-dialog"
      data-testid="agentMgmt.remove.dialog"
    >
      <h3>{T.removeTitle(agent.name)}</h3>
      {/* what exactly happens, BEFORE confirming (spec §3.2). */}
      <p data-testid="agentMgmt.remove.consequences">{T.removeConsequences}</p>

      <div role="radiogroup" aria-label={T.labelWorktree} data-testid="agentMgmt.remove.worktreeChoice">
        <label>
          <input
            type="radio"
            checked={fate === 'keep'}
            onChange={() => setFate('keep')}
            data-testid="agentMgmt.remove.worktreeChoice.keep"
          />
          {T.worktreeKeep}
        </label>
        <label>
          <input
            type="radio"
            checked={fate === 'delete'}
            onChange={() => setFate('delete')}
            data-testid="agentMgmt.remove.worktreeChoice.delete"
          />
          {T.worktreeDelete}
        </label>
      </div>

      {/* data-loss warning — only on the destructive path; may wrap, never ellipsis (spec §3.4). */}
      {fate === 'delete' && (
        <p className="agent-mgmt-worktree-warning" role="alert" data-testid="agentMgmt.remove.worktreeWarning">
          {worktreeDeleteWarning(agent.worktree)}
        </p>
      )}

      {error !== null && (
        <p className="agent-mgmt-error" role="alert" data-testid="agentMgmt.remove.error">
          {error}
        </p>
      )}

      <div className="agent-mgmt-actions">
        {/* cancel is the default / first-focused; destructive confirm is error-toned + named by fate. */}
        <button type="button" data-testid="agentMgmt.remove.cancel" onClick={onCancel} autoFocus>
          {T.cancel}
        </button>
        <button
          type="button"
          className="agent-mgmt-destructive"
          data-testid="agentMgmt.remove.confirm"
          onClick={submit}
          disabled={busy}
        >
          {removeConfirmLabel(fate)}
        </button>
      </div>
    </div>
  )
}
