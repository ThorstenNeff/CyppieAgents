// CYP-649 (P6, Epic CYP-640) — the compact-orchestration panel (whole window). Parity with CMP CompactPanel
// (CYP-326/327/328/329): the global "compact allowed" control (operator checkbox / member read-only chip), a
// mandatory INFO disclosure, the member gate-hint, the operator tunable editors (threshold + 3 timings), and the
// server-mirror facts (status / threshold / last run). Honesty: default OFF, operator-gated, NON-OPTIMISTIC (values
// mirror the server, never the local draft), and UNKNOWN status → the facts + editors render ABSENT, never a
// defaulted "idle"/"off".
import { useEffect, useState } from 'react'
import type { CompactStatus, CompactConfig, CompactRunSummary } from '../types/generated/contract'
import { NumericEditor } from './NumericEditor'
import {
  COMPACT_BOUNDS,
  compactStatusKind,
  lastRunOutcome,
  formatCompactTokens,
  formatDuration,
  type CompactStatusKind,
} from './compactModel'

const statusLabel = (kind: CompactStatusKind, status: CompactStatus | null): string => {
  switch (kind) {
    case 'off':
      return 'aus'
    case 'running':
      return `läuft${status?.lastRun?.startedTs ? ` (seit ${clock(status.lastRun.startedTs)})` : ''}`
    case 'idle':
      return 'bereit'
    case 'unknown':
      return 'unbekannt'
  }
}

const lastRunLabel = (run: CompactRunSummary): string => {
  const outcome = { running: 'läuft', ok: 'ok', timeout: 'Zeitüberschreitung', aborted: 'abgebrochen' }[lastRunOutcome(run)]
  return `${run.completed}/${run.total} — ${outcome}`
}

const clock = (ms: number): string => new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false })

export function CompactPanel({
  status,
  operator,
  onSetConfig,
}: {
  status: CompactStatus | null
  operator: boolean
  onSetConfig: (config: CompactConfig) => Promise<void>
}) {
  const allowed = status?.allowed ?? false // fail-closed default when the server state is unknown
  const kind = compactStatusKind(status)
  const [confirm, setConfirm] = useState<string | null>(null)

  // The transient, post-server confirmation self-clears (never a sticky green "done").
  useEffect(() => {
    if (confirm === null) return
    const t = setTimeout(() => setConfirm(null), 4000)
    return () => clearTimeout(t)
  }, [confirm])

  // Non-optimistic: post the config, and only surface the confirmation once the server accepted it (the parent
  // refetches the status, so the displayed values come from the server, not this draft).
  const set = (config: CompactConfig, confirmMsg: string) =>
    void onSetConfig(config)
      .then(() => setConfirm(confirmMsg))
      .catch(() => undefined)

  return (
    <div className="compact-panel" data-testid="compact-panel">
      {operator ? (
        <label className="compact-allow">
          <input
            type="checkbox"
            data-testid="compact-panel.allow"
            checked={allowed}
            onChange={(e) => set({ allowed: e.target.checked }, e.target.checked ? 'Compact aktiviert' : 'Compact deaktiviert')}
          />{' '}
          Compact erlaubt
        </label>
      ) : (
        <p className="compact-allow-chip" data-testid="compact-panel.allow-chip">
          Compact erlaubt: <span aria-hidden="true">{allowed ? '✓' : '–'}</span> {allowed ? 'ja' : 'nein'}
        </p>
      )}

      <p className="compact-hint compact-hint-info" role="note" data-testid="compact-panel.info">
        Compact fasst lange Agenten-Kontexte zusammen, sobald ein Agent den Token-Schwellwert überschreitet.
      </p>

      {!operator && (
        <p className="compact-hint compact-hint-gated" role="note" data-testid="compact-panel.gate">
          Nur der Operator kann diese Einstellungen ändern.
        </p>
      )}

      {/* Operator tunables — only once the server state resolved (the editors need the current server values). */}
      {operator && status != null && (
        <div className="compact-editors">
          <NumericEditor
            label="Schwellwert (Tokens)"
            current={status.thresholdTokens}
            bounds={COMPACT_BOUNDS.thresholdTokens}
            preview={formatCompactTokens}
            editable
            onSet={(n) => set({ thresholdTokens: n }, `Schwellwert gesetzt: ${formatCompactTokens(n)}`)}
            testId="compact-threshold"
          />
          <NumericEditor
            label="Stagger (ms)"
            current={status.staggerMs ?? COMPACT_BOUNDS.staggerMs.min}
            bounds={COMPACT_BOUNDS.staggerMs}
            preview={formatDuration}
            editable
            onSet={(n) => set({ staggerMs: n }, `Stagger gesetzt: ${formatDuration(n)}`)}
            testId="compact-stagger"
          />
          <NumericEditor
            label="Runden-Pause (ms)"
            current={status.roundGapMs ?? COMPACT_BOUNDS.roundGapMs.min}
            bounds={COMPACT_BOUNDS.roundGapMs}
            preview={formatDuration}
            editable
            onSet={(n) => set({ roundGapMs: n }, `Runden-Pause gesetzt: ${formatDuration(n)}`)}
            testId="compact-roundgap"
          />
          <NumericEditor
            label="Runden-Fenster (ms)"
            current={status.roundWindowMs ?? COMPACT_BOUNDS.roundWindowMs.min}
            bounds={COMPACT_BOUNDS.roundWindowMs}
            preview={formatDuration}
            editable
            onSet={(n) => set({ roundWindowMs: n }, `Runden-Fenster gesetzt: ${formatDuration(n)}`)}
            testId="compact-roundwindow"
          />
        </div>
      )}

      {/* Server-mirror facts — rendered ONLY when the server state resolved; UNKNOWN → absent (never a default). */}
      {status != null && (
        <dl className="compact-facts" data-testid="compact-panel.facts">
          <div className="compact-fact">
            <dt>Status</dt>
            <dd data-testid="compact-panel.status">{statusLabel(kind, status)}</dd>
          </div>
          <div className="compact-fact">
            <dt>Schwellwert</dt>
            <dd>{formatCompactTokens(status.thresholdTokens)}</dd>
          </div>
          {status.lastRun != null && (
            <div className="compact-fact">
              <dt>Letzter Lauf</dt>
              <dd data-testid="compact-panel.lastrun">{lastRunLabel(status.lastRun)}</dd>
            </div>
          )}
        </dl>
      )}

      {confirm != null && (
        <p className="compact-confirm" role="status" data-testid="compact-panel.confirm">
          ✓ {confirm}
        </p>
      )}
    </div>
  )
}
