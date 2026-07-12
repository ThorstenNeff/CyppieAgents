// CYP-461 (P2-g) — the connector picker + B (mcp) opt-in dialog. Ported from Compose ConnectorPicker/OptInDialog.
// Host: the ALREADY operator-gated agent-config dialog (CYP-450) — no second gate (§3.3); `editable` is inherited.
// The honesty (spec §3/§4, teeth 1-5):
//   - A (stream_json) is the first-class default (preselected); B (mcp) is NEVER preselected;
//   - selecting B does NOT switch — it opens the opt-in dialog; B settles ONLY via the ack-gated confirm;
//   - the confirm is enabled ONLY when editable ∧ risk-acknowledged ∧ the ADVISORY capability preview is visibly
//     loaded (fail-closed: no preview ⇒ no confirm — you can't acknowledge a risk you can't see);
//   - the three risk lines are amber ATTENTION (a disclosure of a chosen thing), never error-red; the error line is red;
//   - the advisory preview (scope 'preview') is the expected profile per kind, NEVER a guarantee and never mixed with
//     the observed Agent.capabilities (scope agentId, P2-a) — a different node entirely (§2).
import { useEffect, useState } from 'react'
import type { Capabilities, ConnectorsView } from '../types/generated/contract'
import {
  CAPABILITY_DIMENSIONS,
  dimensionLabel,
  statusLabel,
  previewCapabilities,
  canConfirmOptIn,
  connectorKindLabel,
  OPTIN_RISKS,
  CONNECTOR_TEXT as T,
  type ConnectorKind,
} from './connectorModel'

export interface ConnectorPickerProps {
  /** the SETTLED draft kind — aria-checked reflects this, never a click echo (spec §11). */
  kind: ConnectorKind
  /** the current server-side kind (for the edit effect-hint; equals `kind` at add-start → no hint). */
  initialKind: ConnectorKind
  mode: 'add' | 'edit'
  editable: boolean
  getConnectors: () => Promise<ConnectorsView>
  /** A-select settles immediately; B settles only through the opt-in confirm. Async so the edit commit
   *  (POST /connector) can reject into the dialog's error line (§4.6). */
  onConfirm: (kind: ConnectorKind) => Promise<void>
}

export function ConnectorPicker({ kind, initialKind, mode, editable, getConnectors, onConfirm }: ConnectorPickerProps) {
  const [optInOpen, setOptInOpen] = useState(false)

  return (
    <div className="connector-picker" data-testid="connector.picker">
      <span className="connector-picker-label">{T.pickerLabel}</span>
      <div role="radiogroup" aria-label={T.pickerLabel}>
        <label>
          <input
            type="radio"
            data-testid="connector.picker.streamJson"
            checked={kind === 'stream_json'}
            aria-checked={kind === 'stream_json'}
            disabled={!editable}
            onChange={() => void onConfirm('stream_json')} // A settles immediately (no ack, no dialog)
          />
          {connectorKindLabel('stream_json')}
        </label>
        <label>
          <input
            type="radio"
            data-testid="connector.picker.mcp"
            checked={kind === 'mcp'} // reflects the SETTLED draft — selecting B below does NOT check it
            aria-checked={kind === 'mcp'}
            disabled={!editable}
            onChange={() => setOptInOpen(true)} // B opens the opt-in; it does NOT switch the connector
          />
          {connectorKindLabel('mcp')}
        </label>
      </div>

      <p className="connector-default-note" role="note" data-testid="connector.picker.defaultNote">
        {T.defaultNote}
      </p>

      {/* edit-only amber "saved ≠ active" → restart (reuse CYP-88); shown when the draft differs from the server kind. */}
      {mode === 'edit' && kind !== initialKind && (
        <p className="connector-effect-hint" role="status" data-testid="connector.picker.effectHint">
          {T.effectHint}
        </p>
      )}

      {optInOpen && (
        <OptInDialog
          editable={editable}
          getConnectors={getConnectors}
          onConfirm={async () => {
            await onConfirm('mcp')
            setOptInOpen(false)
          }}
          onCancel={() => setOptInOpen(false)}
        />
      )}
    </div>
  )
}

function CapabilityPreview({ caps }: { caps: Capabilities }) {
  return (
    <ul className="connector-capability-preview" data-testid="connector.optInDialog.capabilityPreview">
      {CAPABILITY_DIMENSIONS.map((dim) => (
        <li key={dim} data-testid={`connector.preview.capability.${dim}`}>
          <span className="connector-cap-label">{dimensionLabel(dim)}</span>
          {/* colour never alone — the status is a text label + a toned chip (§8). */}
          <span
            className={`connector-cap-chip connector-cap-${caps[dim]}`}
            data-testid={`connector.preview.capability.${dim}.status`}
            data-status={caps[dim]}
          >
            {statusLabel(caps[dim])}
          </span>
        </li>
      ))}
    </ul>
  )
}

function OptInDialog({
  editable,
  getConnectors,
  onConfirm,
  onCancel,
}: {
  editable: boolean
  getConnectors: () => Promise<ConnectorsView>
  onConfirm: () => Promise<void>
  onCancel: () => void
}) {
  const [view, setView] = useState<ConnectorsView | null>(null)
  const [loadFailed, setLoadFailed] = useState(false)
  const [ack, setAck] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [confirmError, setConfirmError] = useState(false)
  const [loadNonce, setLoadNonce] = useState(0)

  useEffect(() => {
    let live = true
    setLoadFailed(false)
    getConnectors()
      .then((v) => live && setView(v))
      .catch(() => live && setLoadFailed(true))
    return () => {
      live = false
    }
  }, [getConnectors, loadNonce])

  const preview = previewCapabilities(view, 'mcp')
  const previewLoaded = preview !== null
  const canConfirm = canConfirmOptIn(editable, ack, previewLoaded) && !submitting

  const submit = async () => {
    setConfirmError(false)
    setSubmitting(true)
    try {
      await onConfirm()
    } catch {
      setConfirmError(true) // a REAL error (§4.6) — distinct from the amber risk disclosure
      setSubmitting(false)
    }
  }

  return (
    <div role="dialog" aria-label={T.optinTitle} className="connector-optin" data-testid="connector.optInDialog">
      <h3>{T.optinTitle}</h3>
      <p>{T.optinIntro}</p>

      {/* three named risks — amber ATTENTION (a chosen thing's disclosure), NOT error-red (§4.2, tooth 5). */}
      <p className="connector-risk" role="note" data-testid="connector.optInDialog.riskBypass">{OPTIN_RISKS.bypass}</p>
      <p className="connector-risk" role="note" data-testid="connector.optInDialog.riskAccount">{OPTIN_RISKS.account}</p>
      <p className="connector-risk" role="note" data-testid="connector.optInDialog.riskFragile">{OPTIN_RISKS.fragile}</p>

      {/* the advisory preview — what B gives up, BEFORE the act (§0/§4.3). Fail-closed if it won't load. */}
      <p className="connector-preview-title">{T.previewTitle}</p>
      {preview !== null ? (
        <CapabilityPreview caps={preview} />
      ) : loadFailed ? (
        <div className="connector-preview-failed" role="alert" data-testid="connector.optInDialog.previewFailed">
          <span>{T.previewLoadFailed}</span>
          <button type="button" data-testid="connector.optInDialog.previewRetry" onClick={() => setLoadNonce((n) => n + 1)}>
            Erneut versuchen
          </button>
        </div>
      ) : (
        <p className="connector-preview-loading">…</p>
      )}

      {/* deliberate acknowledgement — whole row is the hit-area, never a default (§4.4). */}
      <label className="connector-ack">
        <input
          type="checkbox"
          data-testid="connector.optInDialog.ack"
          checked={ack}
          disabled={!editable}
          onChange={(e) => setAck(e.target.checked)}
        />
        {T.ack}
      </label>

      {/* load-bearing anti-injection note, verbatim + visible (§4.5/§6). */}
      <p className="connector-human-only" role="note" data-testid="connector.optInDialog.humanOnly">
        {T.humanOnly}
      </p>

      {confirmError && (
        <p className="connector-optin-error" role="alert" data-testid="connector.optInDialog.error">
          {T.optinError}
        </p>
      )}

      <div className="connector-optin-actions">
        <button type="button" data-testid="connector.optInDialog.cancel" onClick={onCancel}>
          {T.cancel}
        </button>
        <button
          type="button"
          data-testid="connector.optInDialog.confirm"
          disabled={!canConfirm}
          aria-disabled={!canConfirm}
          onClick={submit}
        >
          {T.confirm}
        </button>
      </div>
    </div>
  )
}
