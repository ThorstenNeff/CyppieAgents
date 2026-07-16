// CYP-657 (P9, Epic CYP-640) — the Per-Agent-Settings panel, web-ts port of the CMP AgentSettingsPanel. Three
// sections for the selected agent: (1) display-colour picker (swatches + custom hex + contrast guard + adapted
// preview), (2) CLAUDE.md re-edit with a server-authoritative conflict dialog (no silent clobber), (3) worktree-path
// display. Operator-gate is present-but-disabled (mirrors AgentManagementPanel): the surface is visible to everyone,
// mutation controls are disabled + a gate hint shows for a non-operator — never omission. All honesty rules live in
// agentSettingsModel (tested); this file is orchestration + presentation.
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { Agent, AgentDetail, ClaudeMdView } from '../types/generated/contract'
import {
  AGENT_SETTINGS_TEXT as T,
  AGENT_SETTINGS_TESTID as TID,
  COLOR_SWATCHES,
  evaluateColor,
  normalizeHex,
  readableAccentOn,
  SURFACE_LIGHT,
  SURFACE_DARK,
  expectedVersion,
  isClaudeMdDirty,
  classifyWriteError,
  baselineFromView,
  claudeMdHint,
  worktreeZone,
} from './agentSettingsModel'

export interface AgentSettingsPanelProps {
  agents: readonly Agent[]
  operator: boolean
  /** Which theme surface the preview paints against (App resolves it from the theme mode). Advisory only — the guard
   *  itself always checks BOTH themes regardless of this. */
  previewSurface?: 'light' | 'dark'
  /** GET /api/agents/{id} — the full detail (color + worktreePath) for the selected agent. */
  fetchDetail: (id: string) => Promise<AgentDetail>
  /** PUT /api/agents/{id} with just the colour (AgentEdit.color). Non-optimistic: resolves on server-confirm. */
  onSaveColor: (id: string, color: string) => Promise<void>
  /** GET /api/agents/{id}/claude-md — the live persona file + version. */
  getClaudeMd: (id: string) => Promise<ClaudeMdView>
  /** POST /api/agents/{id}/claude-md — write with an if-match; returns the fresh echo. Rejects with 409 on drift. */
  updateClaudeMd: (id: string, content: string, expectedVersion: string | null) => Promise<ClaudeMdView>
}

export function AgentSettingsPanel(props: AgentSettingsPanelProps) {
  const { agents, operator } = props
  const [selectedId, setSelectedId] = useState<string>(agents[0]?.id ?? '')

  // Keep a valid selection as the roster changes (an agent removed out from under us falls back to the first).
  useEffect(() => {
    if (agents.length > 0 && !agents.some((a) => a.id === selectedId)) setSelectedId(agents[0].id)
  }, [agents, selectedId])

  if (agents.length === 0) {
    return (
      <div className="agent-settings" data-testid={TID.panel}>
        <h2>{T.title}</h2>
      </div>
    )
  }

  return (
    <div className="agent-settings" data-testid={TID.panel}>
      <div className="agent-settings-header">
        <h2>{T.title}</h2>
        <label className="agent-settings-picker-label">
          {T.agentPickerLabel}
          <select
            data-testid={TID.agentPicker}
            value={selectedId}
            onChange={(e) => setSelectedId(e.target.value)}
          >
            {agents.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
        </label>
      </div>

      {!operator && (
        <p className="agent-settings-gate" role="note" data-testid={TID.gate}>
          {T.operatorRequired}
        </p>
      )}

      {/* Remount the per-agent sections on selection change so their load effects re-run cleanly. */}
      <AgentSections key={selectedId} agentId={selectedId} {...props} />
    </div>
  )
}

function AgentSections({
  agentId,
  operator,
  previewSurface = 'light',
  fetchDetail,
  onSaveColor,
  getClaudeMd,
  updateClaudeMd,
}: AgentSettingsPanelProps & { agentId: string }) {
  return (
    <>
      <ColorSection
        agentId={agentId}
        operator={operator}
        previewSurface={previewSurface}
        fetchDetail={fetchDetail}
        onSaveColor={onSaveColor}
      />
      <ClaudeMdSection
        agentId={agentId}
        operator={operator}
        getClaudeMd={getClaudeMd}
        updateClaudeMd={updateClaudeMd}
      />
      <WorktreeSection agentId={agentId} fetchDetail={fetchDetail} />
    </>
  )
}

// ── Colour ───────────────────────────────────────────────────────────────────────────────────────────────────────
function ColorSection({
  agentId,
  operator,
  previewSurface,
  fetchDetail,
  onSaveColor,
}: {
  agentId: string
  operator: boolean
  previewSurface: 'light' | 'dark'
  fetchDetail: (id: string) => Promise<AgentDetail>
  onSaveColor: (id: string, color: string) => Promise<void>
}) {
  const [hexInput, setHexInput] = useState('')
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)

  useEffect(() => {
    let live = true
    fetchDetail(agentId)
      .then((d) => {
        if (live && d.color) setHexInput(d.color)
      })
      .catch(() => undefined)
    return () => {
      live = false
    }
  }, [agentId, fetchDetail])

  const verdict = useMemo(() => (hexInput.trim() === '' ? null : evaluateColor(hexInput)), [hexInput])
  const normalized = normalizeHex(hexInput)
  const surfaceHex = previewSurface === 'dark' ? SURFACE_DARK : SURFACE_LIGHT
  const adapted = normalized ? readableAccentOn(normalized, surfaceHex) : null

  const canSave = operator && !saving && verdict !== null && verdict.kind !== 'invalid' && normalized !== null

  const save = async () => {
    if (normalized === null) return
    setSaved(false)
    setSaving(true)
    try {
      await onSaveColor(agentId, normalized)
      setSaved(true)
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="agent-settings-section" data-testid={TID.color}>
      <h3>{T.colorHeading}</h3>
      <div className="agent-settings-swatches" role="radiogroup" aria-label={T.colorHeading}>
        {COLOR_SWATCHES.map((sw, i) => {
          const selected = normalized === sw
          return (
            <button
              key={sw}
              type="button"
              className={`agent-settings-swatch${selected ? ' selected' : ''}`}
              data-testid={TID.swatch(i)}
              aria-label={T.colorSwatchLabel(i)}
              aria-pressed={selected}
              disabled={!operator}
              aria-disabled={!operator}
              style={{ background: sw }}
              onClick={() => {
                setSaved(false)
                setHexInput(sw)
              }}
            >
              {selected ? '✓' : ''}
            </button>
          )
        })}
      </div>

      <label className="agent-settings-hex">
        {T.customHexLabel}
        <input
          data-testid={TID.customHexInput}
          value={hexInput}
          placeholder="#4488CC"
          disabled={!operator}
          aria-invalid={verdict?.kind === 'invalid'}
          onChange={(e) => {
            setSaved(false)
            setHexInput(e.target.value)
          }}
        />
      </label>

      {verdict?.kind === 'invalid' && (
        <p className="agent-settings-hint agent-settings-error" role="alert" data-testid={TID.customHexError}>
          {T.customHexInvalid}
        </p>
      )}
      {verdict?.kind === 'degraded' && (
        <p className="agent-settings-hint agent-settings-advisory" role="status" data-testid={TID.contrastAdvisory}>
          {T.contrastDegraded(verdict.worstTheme)}
        </p>
      )}

      {adapted && (
        <div
          className="agent-settings-preview"
          data-testid={TID.preview}
          data-surface={previewSurface}
          style={{ background: surfaceHex }}
        >
          <span className="agent-settings-preview-name" style={{ color: adapted.hex }}>
            {T.previewLabel}
          </span>
          <span
            className="agent-settings-preview-swatch"
            style={{ background: normalized ?? 'transparent' }}
            aria-hidden="true"
          />
        </div>
      )}

      <div className="agent-settings-actions">
        <button type="button" data-testid={TID.saveColor} disabled={!canSave} aria-disabled={!canSave} onClick={save}>
          {T.saveColor}
        </button>
        {saved && (
          <span className="agent-settings-hint agent-settings-effect" role="status" data-testid={TID.effectHint}>
            {T.effectHint}
          </span>
        )}
      </div>
    </section>
  )
}

// ── CLAUDE.md ────────────────────────────────────────────────────────────────────────────────────────────────────
function ClaudeMdSection({
  agentId,
  operator,
  getClaudeMd,
  updateClaudeMd,
}: {
  agentId: string
  operator: boolean
  getClaudeMd: (id: string) => Promise<ClaudeMdView>
  updateClaudeMd: (id: string, content: string, expectedVersion: string | null) => Promise<ClaudeMdView>
}) {
  const [content, setContent] = useState('')
  const [baseline, setBaseline] = useState('')
  const [version, setVersion] = useState<string | null>(null)
  const [exists, setExists] = useState(false)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(false)
  const [writing, setWriting] = useState(false)
  const [written, setWritten] = useState(false)
  const [writeError, setWriteError] = useState(false)
  const [stale, setStale] = useState(false)
  const liveRef = useRef(true)
  // CYP-660: hold the fetchers (+ the live buffer) in refs so `load` depends ONLY on [agentId]. The parent passes NEW
  // getClaudeMd/updateClaudeMd identities every render (inline arrows over an unstable hubRepo); without this the
  // load-useCallback churned → useEffect([load]) re-ran on every WS-tick re-render → the baseline auto-reloaded,
  // clobbering in-progress edits AND advancing the if-match version so drift was never seen (409 never fired). Refs +
  // an [agentId]-only dep make the section CHURN-IMMUNE regardless of parent identity — load runs on mount only.
  const getClaudeMdRef = useRef(getClaudeMd)
  getClaudeMdRef.current = getClaudeMd
  const updateClaudeMdRef = useRef(updateClaudeMd)
  updateClaudeMdRef.current = updateClaudeMd
  const contentRef = useRef(content)
  contentRef.current = content
  const baselineRef = useRef(baseline)
  baselineRef.current = baseline

  const applyView = useCallback((v: ClaudeMdView) => {
    const b = baselineFromView(v)
    setContent(b.baseline)
    setBaseline(b.baseline)
    setVersion(b.version)
    setExists(b.exists)
  }, [])

  // `force` = an EXPLICIT reload (retry / conflict "load live") that discards local. An automatic load with
  // force=false is a NO-OP when the buffer is dirty — belt-and-suspenders that never clobbers an in-progress edit and,
  // crucially, never advances the pinned if-match version under a dirty buffer (advancing it would BE the silent
  // clobber). On mount the buffer is fresh (not dirty), so the first load always applies.
  const load = useCallback(
    (force = false) => {
      setLoading(true)
      setLoadError(false)
      getClaudeMdRef
        .current(agentId)
        .then((v) => {
          if (!liveRef.current) return
          if (!force && isClaudeMdDirty(contentRef.current, baselineRef.current)) return
          applyView(v)
          setWritten(false)
          setWriteError(false)
          setStale(false)
        })
        .catch(() => {
          // fail-closed: an error line, NEVER a blank buffer a save would clobber (failed ≠ empty).
          if (liveRef.current) setLoadError(true)
        })
        .finally(() => {
          if (liveRef.current) setLoading(false)
        })
    },
    [agentId, applyView],
  )

  useEffect(() => {
    liveRef.current = true
    load()
    return () => {
      liveRef.current = false
    }
  }, [load])

  const dirty = isClaudeMdDirty(content, baseline)

  // POST against the CURRENT if-match. On 409 stale → open the conflict dialog (never overwrite silently). `force`
  // (from the conflict dialog's "overwrite anyway") drops the if-match to accept the just-fetched current version.
  const write = async (force: boolean) => {
    setWriteError(false)
    setWriting(true)
    try {
      const ifMatch = force ? version : expectedVersion(exists, version)
      const echo = await updateClaudeMdRef.current(agentId, content, ifMatch)
      if (!liveRef.current) return
      applyView(echo)
      setStale(false)
      setWritten(true) // arms the amber restart hint (saved ≠ active)
    } catch (e) {
      if (!liveRef.current) return
      if (classifyWriteError(e) === 'stale') setStale(true)
      else setWriteError(true)
    } finally {
      if (liveRef.current) setWriting(false)
    }
  }

  // "Overwrite anyway": re-fetch the CURRENT version, then write the local buffer against it (the operator chose to
  // discard the external edit — a deliberate, fresh-if-match overwrite, still not a blind clobber of an unknown state).
  const overwriteAnyway = async () => {
    try {
      const cur = await getClaudeMdRef.current(agentId)
      if (!liveRef.current) return
      setVersion(cur.version ?? null)
      setExists(cur.exists)
      setStale(false)
      await write(true)
    } catch {
      if (liveRef.current) setWriteError(true)
    }
  }

  const hint = claudeMdHint({ saveError: writeError, dirty, saved: written, empty: exists === false && content === '' })

  return (
    <section className="agent-settings-section" data-testid="agentSettings.persona">
      <h3>{T.personaHeading}</h3>

      {loadError ? (
        <div className="agent-settings-loaderror">
          <p className="agent-settings-hint agent-settings-error" role="alert" data-testid={TID.personaLoadError}>
            {T.personaLoadError}
          </p>
          <button type="button" data-testid={TID.personaRetry} onClick={() => load(true)}>
            {T.personaRetry}
          </button>
        </div>
      ) : (
        <textarea
          className="agent-settings-persona-input"
          data-testid={TID.persona}
          value={content}
          disabled={!operator || loading}
          onChange={(e) => {
            setWritten(false)
            setContent(e.target.value)
          }}
        />
      )}

      {!loadError && hint === 'empty' && (
        <p className="agent-settings-hint" role="note" data-testid={TID.personaEmpty}>
          {T.personaEmpty}
        </p>
      )}
      {!loadError && hint === 'unsaved' && (
        <p className="agent-settings-hint agent-settings-effect" role="status" data-testid={TID.personaUnsaved}>
          {T.personaUnsaved}
        </p>
      )}
      {!loadError && hint === 'saveError' && (
        <p className="agent-settings-hint agent-settings-error" role="alert" data-testid={TID.personaSaveError}>
          {T.personaSaveError}
        </p>
      )}
      {!loadError && hint === 'restart' && (
        <p className="agent-settings-hint agent-settings-effect" role="status" data-testid={TID.personaRestart}>
          {T.effectHint}
        </p>
      )}

      {!loadError && (
        <div className="agent-settings-actions">
          <button
            type="button"
            data-testid={TID.savePersona}
            disabled={!operator || writing || loading || !dirty}
            aria-disabled={!operator || writing || loading || !dirty}
            onClick={() => void write(false)}
          >
            {T.savePersona}
          </button>
        </div>
      )}

      {stale && (
        <div
          role="alertdialog"
          aria-label={T.conflictTitle}
          className="agent-settings-conflict"
          data-testid={TID.conflict}
        >
          <h4>{T.conflictTitle}</h4>
          <p>{T.conflictBody}</p>
          <div className="agent-settings-actions">
            {/* reload is the SAFE default (discard local, load current); overwrite is the deliberate, error-toned choice. */}
            <button type="button" data-testid={TID.conflictReload} autoFocus onClick={() => load(true)}>
              {T.conflictReload}
            </button>
            <button
              type="button"
              className="agent-settings-destructive"
              data-testid={TID.conflictOverwrite}
              onClick={() => void overwriteAnyway()}
            >
              {T.conflictOverwrite}
            </button>
          </div>
        </div>
      )}
    </section>
  )
}

// ── Worktree path ────────────────────────────────────────────────────────────────────────────────────────────────
function WorktreeSection({
  agentId,
  fetchDetail,
}: {
  agentId: string
  fetchDetail: (id: string) => Promise<AgentDetail>
}) {
  const [resolved, setResolved] = useState(false)
  const [path, setPath] = useState<string | null>(null)

  useEffect(() => {
    let live = true
    setResolved(false)
    fetchDetail(agentId)
      .then((d) => {
        if (!live) return
        setPath(d.worktreePath ?? null)
        setResolved(true)
      })
      // fail-closed: a failed load stays UNRESOLVED → the zone hides (never claims "not local" from an unknown).
      .catch(() => undefined)
    return () => {
      live = false
    }
  }, [agentId, fetchDetail])

  const zone = worktreeZone(resolved, path)
  if (zone === 'hidden') return null

  return (
    <section className="agent-settings-section" data-testid="agentSettings.worktree">
      <h3>{T.worktreeHeading}</h3>
      {zone === 'notLocal' ? (
        <p className="agent-settings-hint" role="note" data-testid={TID.worktreeNotLocal}>
          {T.worktreeNotLocal}
        </p>
      ) : (
        <div className="agent-settings-worktree-row">
          <code className="agent-settings-worktree-path" data-testid={TID.worktree}>
            {path}
          </code>
        </div>
      )}
    </section>
  )
}
