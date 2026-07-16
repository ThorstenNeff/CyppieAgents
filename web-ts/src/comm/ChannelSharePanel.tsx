// CYP-659 (P11, Epic CYP-640) — the Cross-Project Channel-Share panel, web-ts port of the CMP CrossProjectControls.
// One row per channel: the share badge + status + owning project (read-tier — a member sees these), and the
// operator-gated grantee editor (share to other projects) + revoke. Operator-gate is present-but-disabled (mirrors
// AclPanel/ProjectManagementPanel): the surface + status show for everyone with read access; only authorize/revoke are
// gated — never omission. All honesty rules live in channelShareModel (tested); this file is orchestration.
import { useCallback, useEffect, useRef, useState } from 'react'
import type { Channel, ChannelShareView, Project } from '../types/generated/contract'
import {
  CHANNEL_SHARE_TEXT as T,
  CHANNEL_SHARE_TESTID as TID,
  granteeCandidates,
  sanitizeGrantees,
  currentGranteeIds,
  isShared,
  isRevokingSet,
  shareMutationMessage,
} from './channelShareModel'

export interface ChannelSharePanelProps {
  channels: readonly Channel[]
  projects: readonly Project[]
  operator: boolean
  /** GET /api/channels/{id}/share (read-tier). */
  getShare: (channelId: string) => Promise<ChannelShareView>
  /** PUT /api/channels/{id}/share {sharedWith} → the fresh echo (non-optimistic). Empty set revokes. */
  onShare: (channelId: string, sharedWith: string[]) => Promise<ChannelShareView>
  /** DELETE /api/channels/{id}/share → the fresh echo ({shared:false}) — 200, not 204 (non-optimistic re-sync). */
  onUnshare: (channelId: string) => Promise<ChannelShareView>
}

export function ChannelSharePanel({ channels, projects, operator, getShare, onShare, onUnshare }: ChannelSharePanelProps) {
  return (
    <div className="channel-share" data-testid={TID.panel}>
      <div className="channel-share-header">
        <h2>{T.title}</h2>
        <p className="channel-share-subtitle">{T.subtitle}</p>
      </div>
      {!operator && (
        <p className="channel-share-gate" role="note" data-testid={TID.gate}>
          {T.operatorRequired}
        </p>
      )}
      {channels.length === 0 ? (
        <p className="channel-share-empty" role="note" data-testid={TID.empty}>
          —
        </p>
      ) : (
        <ul className="channel-share-list">
          {channels.map((ch) => (
            <ChannelShareRow
              key={ch.id}
              channel={ch}
              projects={projects}
              operator={operator}
              getShare={getShare}
              onShare={onShare}
              onUnshare={onUnshare}
            />
          ))}
        </ul>
      )}
    </div>
  )
}

type Confirm = null | { kind: 'revoke' } | { kind: 'emptySave' }

function ChannelShareRow({
  channel,
  projects,
  operator,
  getShare,
  onShare,
  onUnshare,
}: {
  channel: Channel
  projects: readonly Project[]
  operator: boolean
  getShare: (channelId: string) => Promise<ChannelShareView>
  onShare: (channelId: string, sharedWith: string[]) => Promise<ChannelShareView>
  onUnshare: (channelId: string) => Promise<ChannelShareView>
}) {
  const id = channel.id
  const [view, setView] = useState<ChannelShareView | null>(null)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [confirm, setConfirm] = useState<Confirm>(null)
  const liveRef = useRef(true)
  // churn-immune (per CYP-660): the parent passes a new getShare identity each render → ref it so the load effect
  // depends only on [id] and runs on mount, not on every re-render.
  const getShareRef = useRef(getShare)
  getShareRef.current = getShare

  const candidates = granteeCandidates(projects, channel.projectId)
  const candidateIds = new Set(candidates.map((p) => p.id))

  // Non-optimistic: seed the state from the SERVER echo (view + the grantee ids its reachableScope implies).
  const applyView = useCallback((v: ChannelShareView) => {
    setView(v)
    setSelected(new Set(currentGranteeIds(v)))
  }, [])

  const load = useCallback(() => {
    setLoading(true)
    setLoadError(false)
    getShareRef
      .current(id)
      .then((v) => {
        if (liveRef.current) applyView(v)
      })
      // fail-closed: an error line, never a guessed share-state the controls would act on.
      .catch(() => {
        if (liveRef.current) setLoadError(true)
      })
      .finally(() => {
        if (liveRef.current) setLoading(false)
      })
  }, [id, applyView])

  useEffect(() => {
    liveRef.current = true
    load()
    return () => {
      liveRef.current = false
    }
  }, [load])

  const toggle = (projectId: string) => {
    setError(null)
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(projectId)) next.delete(projectId)
      else next.add(projectId)
      return next
    })
  }

  // PUT the sanitized grantee set. Empty sanitized set = a revoke → confirm first (never a silent wipe).
  const doShare = async (force = false) => {
    const sanitized = sanitizeGrantees([...selected], candidateIds)
    if (isRevokingSet(sanitized) && isShared(view) && !force) {
      setConfirm({ kind: 'emptySave' })
      return
    }
    setConfirm(null)
    setError(null)
    setBusy(true)
    try {
      const echo = await onShare(id, sanitized)
      if (liveRef.current) applyView(echo) // non-optimistic re-sync from the server echo
    } catch (e) {
      if (liveRef.current) setError(shareMutationMessage(e))
    } finally {
      if (liveRef.current) setBusy(false)
    }
  }

  const doRevoke = async () => {
    setConfirm(null)
    setError(null)
    setBusy(true)
    try {
      const echo = await onUnshare(id)
      if (liveRef.current) applyView(echo)
    } catch (e) {
      if (liveRef.current) setError(shareMutationMessage(e))
    } finally {
      if (liveRef.current) setBusy(false)
    }
  }

  const shared = isShared(view)
  const reach = view?.reachableScope ?? []

  return (
    <li className="channel-share-row" data-testid={TID.row(id)}>
      <div className="channel-share-row-head">
        <span className="channel-share-name">{channel.name}</span>
        <span className={`channel-share-badge${shared ? ' shared' : ''}`} data-testid={TID.badge(id)}>
          <span aria-hidden="true">⇄</span> {shared ? T.sharedBadge : T.notSharedBadge}
        </span>
      </div>
      {channel.projectId !== undefined && channel.projectId !== null && (
        <p className="channel-share-owner" data-testid={TID.ownerProject(id)}>
          {T.ownerProjectLabel(channel.projectId)}
        </p>
      )}

      {loadError ? (
        <div className="channel-share-loaderror">
          <p className="channel-share-error" role="alert" data-testid={TID.loadError(id)}>
            {T.loadError}
          </p>
          <button type="button" onClick={load}>
            {T.retry}
          </button>
        </div>
      ) : (
        <>
          <p className="channel-share-status" role="status" data-testid={TID.status(id)}>
            {shared ? `${T.sharedStatus(currentGranteeIds(view).length)}${view?.sharedAt != null ? ` · ${T.sharedAt(new Date(view.sharedAt).toLocaleTimeString())}` : ''}` : T.notSharedStatus}
          </p>

          {shared && reach.length > 0 && (
            <ul className="channel-share-reach" data-testid={TID.reach(id)}>
              {reach.map((a) => (
                <li key={a.agentId} data-testid={TID.reachEntry(id, a.agentId)}>
                  {T.reachEntry(a.agentId, a.projectId, a.access)}
                </li>
              ))}
            </ul>
          )}

          {/* grantee editor + actions are operator-only (present-but-disabled: a member sees status/badge, not these). */}
          {operator && (
            <>
              {candidates.length === 0 ? (
                <p className="channel-share-hint" role="note">
                  {T.noCandidates}
                </p>
              ) : (
                <fieldset className="channel-share-grantees">
                  <legend>{T.granteeHeading}</legend>
                  {candidates.map((p) => (
                    <label key={p.id}>
                      <input
                        type="checkbox"
                        data-testid={TID.grantee(id, p.id)}
                        checked={selected.has(p.id)}
                        disabled={busy || loading}
                        onChange={() => toggle(p.id)}
                      />
                      {p.name}
                    </label>
                  ))}
                </fieldset>
              )}

              <div className="channel-share-actions">
                <button type="button" data-testid={TID.save(id)} disabled={busy || loading || candidates.length === 0} onClick={() => void doShare(false)}>
                  {T.save}
                </button>
                {shared && (
                  <button
                    type="button"
                    className="channel-share-destructive"
                    data-testid={TID.revoke(id)}
                    disabled={busy}
                    onClick={() => setConfirm({ kind: 'revoke' })}
                  >
                    {T.revoke}
                  </button>
                )}
              </div>

              {error !== null && (
                <p className="channel-share-error" role="alert" data-testid={TID.error(id)}>
                  {error}
                </p>
              )}

              {confirm !== null && (
                <div role="alertdialog" aria-label={T.revoke} className="channel-share-confirm" data-testid={TID.confirm(id)}>
                  <p>{confirm.kind === 'revoke' ? T.revokeConfirm : T.saveRevokeConfirm}</p>
                  <div className="channel-share-actions">
                    <button type="button" data-testid={TID.confirmCancel(id)} autoFocus onClick={() => setConfirm(null)}>
                      {T.cancel}
                    </button>
                    <button
                      type="button"
                      className="channel-share-destructive"
                      data-testid={TID.confirmOk(id)}
                      disabled={busy}
                      onClick={() => void (confirm.kind === 'revoke' ? doRevoke() : doShare(true))}
                    >
                      {T.revokeConfirmButton}
                    </button>
                  </div>
                </div>
              )}
            </>
          )}
        </>
      )}
    </li>
  )
}
