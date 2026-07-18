// CYP-651 (P8, Epic CYP-640) — the OPERATOR-only project management window. Parity with CMP ProjectManagementPanel
// (CYP-91/S13): a list of projects with switch / rename / delete + add, all operator-gated. The DELETE is the only
// destructive action; its guardrails are shown BEFORE the action (active + last projects have delete disabled with
// an inline reason) and the hard delete is armed by a NAME-ECHO confirm (client UX guard — there is no server
// confirm token). Non-optimistic: every mutation refetches the projects; a switch stays PENDING until the 200.
//
// Correctness boundary is the SERVER ProjectGuard — this UI only preempts doomed/dangerous calls and disables what
// the pre-check knows is blocked; the caller still surfaces all 3 server codes first-class.
import { useState } from 'react'
import type { ProjectsView } from '../types/generated/contract'
import { deleteBlockedReason, canFireProjectDelete, projectMutationMessage } from './projectModel'
import { restErrorCode } from '../net/rest'
import { LoadErrorRetry } from '../ui/LoadErrorRetry'

export interface ProjectMgmtProps {
  projects: ProjectsView | null
  operator: boolean
  // CYP-679: a failed projects load else shows "Projekte werden geladen…" FOREVER (loading-that-actually-failed).
  loadError?: boolean
  onRetryLoad?: () => void
  onCreate: (id: string, name: string) => Promise<void>
  onSwitch: (projectId: string) => Promise<void>
  onRename: (id: string, name: string) => Promise<void>
  onDelete: (id: string, deleteWorktrees: boolean) => Promise<void>
}

const blockedReasonText = (r: 'last' | 'active'): string =>
  r === 'last' ? 'Das letzte Projekt kann nicht gelöscht werden.' : 'Aktives Projekt — erst wechseln, dann löschbar.'

export function ProjectManagementPanel({ projects, operator, loadError = false, onRetryLoad, onCreate, onSwitch, onRename, onDelete }: ProjectMgmtProps) {
  const [error, setError] = useState<string | null>(null)
  const [pendingSwitch, setPendingSwitch] = useState<string | null>(null)
  const [addOpen, setAddOpen] = useState(false)
  const [renameTarget, setRenameTarget] = useState<string | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null)

  if (!operator) {
    return (
      <div className="project-mgmt" data-testid="project-mgmt">
        <p className="project-gate-hint" role="note" data-testid="project-mgmt.gate">
          Nur der Operator kann Projekte verwalten.
        </p>
      </div>
    )
  }
  if (projects === null) {
    // CYP-679: distinguish a failed load (error+retry) from the genuine loading placeholder — else a failed load
    // reads as "loading…" forever. (Only an operator reaches here — the gate hint returns above.)
    return (
      <div className="project-mgmt" data-testid="project-mgmt">
        {loadError ? (
          <LoadErrorRetry testId="project-mgmt.loadError" onRetry={onRetryLoad ?? (() => undefined)} />
        ) : (
          <p className="project-empty" role="status" data-testid="project-mgmt.loading">
            Projekte werden geladen…
          </p>
        )}
      </div>
    )
  }

  const list = projects.projects
  const activeId = projects.activeProjectId
  const run = (p: Promise<void>) => p.then(() => setError(null)).catch((e) => setError(projectMutationMessage(restErrorCode(e))))

  return (
    <div className="project-mgmt" data-testid="project-mgmt">
      <div className="project-mgmt-head">
        <h3 className="project-title">Projekte</h3>
        <button type="button" data-testid="project-mgmt.add" onClick={() => setAddOpen((v) => !v)}>
          + Projekt
        </button>
      </div>

      {addOpen && <AddForm onCreate={(id, n) => run(onCreate(id, n)).then(() => setAddOpen(false))} onCancel={() => setAddOpen(false)} />}

      <ul className="project-list" data-testid="project-mgmt.list">
        {list.map((p) => {
          const isActive = p.id === activeId
          const blocked = deleteBlockedReason(p.id, activeId, list.length)
          return (
            <li className="project-row" key={p.id} data-testid={`project-mgmt.row.${p.id}`}>
              <div className="project-identity">
                {isActive && (
                  <span className="project-active-marker" data-testid={`project-mgmt.active.${p.id}`} aria-label="aktiv">
                    ●
                  </span>
                )}
                <span className="project-name">{p.name}</span>
              </div>
              <div className="project-actions">
                <button
                  type="button"
                  data-testid={`project-mgmt.switch.${p.id}`}
                  disabled={isActive || pendingSwitch !== null}
                  onClick={() => {
                    // Non-optimistic: keep it pending until the 200; the pointer flips only on the refetched view.
                    setPendingSwitch(p.id)
                    void run(onSwitch(p.id)).finally(() => setPendingSwitch(null))
                  }}
                >
                  {pendingSwitch === p.id ? 'Wechselt…' : 'Wechseln'}
                </button>
                <button type="button" data-testid={`project-mgmt.rename.${p.id}`} onClick={() => setRenameTarget(renameTarget === p.id ? null : p.id)}>
                  Umbenennen
                </button>
                <button
                  type="button"
                  className="project-delete-btn"
                  data-testid={`project-mgmt.delete.${p.id}`}
                  disabled={blocked !== null}
                  onClick={() => setDeleteTarget(deleteTarget === p.id ? null : p.id)}
                >
                  Löschen
                </button>
              </div>
              {blocked !== null && (
                <p className="project-blocked" role="note" data-testid={`project-mgmt.blocked.${p.id}`}>
                  {blockedReasonText(blocked)}
                </p>
              )}
              {renameTarget === p.id && (
                <RenameForm current={p.name} onRename={(n) => run(onRename(p.id, n)).then(() => setRenameTarget(null))} onCancel={() => setRenameTarget(null)} />
              )}
              {deleteTarget === p.id && blocked === null && (
                <DeleteForm
                  project={{ id: p.id, name: p.name }}
                  activeId={activeId}
                  projectCount={list.length}
                  onDelete={(worktrees) => run(onDelete(p.id, worktrees)).then(() => setDeleteTarget(null))}
                  onCancel={() => setDeleteTarget(null)}
                />
              )}
            </li>
          )
        })}
      </ul>

      {error !== null && (
        <p className="project-error" role="alert" data-testid="project-mgmt.error">
          {error}
        </p>
      )}
    </div>
  )
}

function AddForm({ onCreate, onCancel }: { onCreate: (id: string, name: string) => void; onCancel: () => void }) {
  const [id, setId] = useState('')
  const [name, setName] = useState('')
  const valid = id.trim() !== '' && name.trim() !== ''
  return (
    <div className="project-form" data-testid="project-mgmt.add-form">
      <input data-testid="project-mgmt.add-id" placeholder="Projekt-ID" value={id} onChange={(e) => setId(e.target.value)} />
      <input data-testid="project-mgmt.add-name" placeholder="Anzeigename" value={name} onChange={(e) => setName(e.target.value)} />
      <button type="button" data-testid="project-mgmt.add-confirm" disabled={!valid} onClick={() => onCreate(id.trim(), name.trim())}>
        Anlegen
      </button>
      <button type="button" onClick={onCancel}>
        Abbrechen
      </button>
    </div>
  )
}

function RenameForm({ current, onRename, onCancel }: { current: string; onRename: (name: string) => void; onCancel: () => void }) {
  const [name, setName] = useState(current)
  const valid = name.trim() !== '' && name.trim() !== current
  return (
    <div className="project-form" data-testid="project-mgmt.rename-form">
      <input data-testid="project-mgmt.rename-name" value={name} onChange={(e) => setName(e.target.value)} />
      <button type="button" data-testid="project-mgmt.rename-confirm" disabled={!valid} onClick={() => onRename(name.trim())}>
        Umbenennen
      </button>
      <button type="button" onClick={onCancel}>
        Abbrechen
      </button>
    </div>
  )
}

function DeleteForm({
  project,
  activeId,
  projectCount,
  onDelete,
  onCancel,
}: {
  project: { id: string; name: string }
  activeId: string
  projectCount: number
  onDelete: (deleteWorktrees: boolean) => void
  onCancel: () => void
}) {
  const [nameEcho, setNameEcho] = useState('')
  const [deleteWorktrees, setDeleteWorktrees] = useState(false)
  // The load-bearing client tooth: the DELETE button is armed ONLY when the pure gate says so (not-blocked AND the
  // name-echo matches). deleteWorktrees is orthogonal (kept default false = safe).
  const armed = canFireProjectDelete({ projectId: project.id, activeProjectId: activeId, projectCount, nameEcho, projectName: project.name })
  return (
    <div className="project-delete-form" data-testid="project-mgmt.delete-form">
      <p className="project-delete-consequences" role="note">
        Löscht Projekt „{project.name}" unwiderruflich (Config + Events{deleteWorktrees ? ' + Worktrees' : ''}).
      </p>
      <label className="project-delete-worktrees">
        <input type="checkbox" data-testid="project-mgmt.delete-worktrees" checked={deleteWorktrees} onChange={(e) => setDeleteWorktrees(e.target.checked)} />{' '}
        Worktrees ebenfalls löschen (Standard: behalten)
      </label>
      <label className="project-delete-echo-label" htmlFor={`delete-echo-${project.id}`}>
        Zum Bestätigen den Projektnamen eingeben:
      </label>
      <input
        id={`delete-echo-${project.id}`}
        data-testid="project-mgmt.delete-echo"
        placeholder={project.name}
        value={nameEcho}
        onChange={(e) => setNameEcho(e.target.value)}
      />
      <button type="button" className="project-delete-confirm" data-testid="project-mgmt.delete-confirm" disabled={!armed} onClick={() => onDelete(deleteWorktrees)}>
        Endgültig löschen
      </button>
      <button type="button" onClick={onCancel}>
        Abbrechen
      </button>
    </div>
  )
}
