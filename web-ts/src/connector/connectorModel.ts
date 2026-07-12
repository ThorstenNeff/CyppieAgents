// CYP-461 (P2-g) — the connector-picker honesty core (pure). Ported from Compose ConnectorModel/ConnectorSelectionVM.
// The load-bearing rules (spec §0-§6): A (stream_json) is the first-class default; B (mcp) is NEVER preselected and
// only settles through the ack-gated opt-in whose ADVISORY capability preview (from GET /api/connectors) makes the
// risk-acknowledgement meaningful — fail-closed: no visible preview ⇒ no confirm. The advisory pre-choice preview
// (scope 'preview') and the observed Agent.capabilities (scope agentId) are two registers, NEVER mixed (§2).
import type { Capabilities, ConnectorsView } from '../types/generated/contract'

export type ConnectorKind = 'stream_json' | 'mcp'
export type CapabilityStatus = 'available' | 'limited' | 'unavailable'

export function connectorKindLabel(kind: ConnectorKind): string {
  return kind === 'stream_json' ? 'Stream-JSON (voll)' /* connector_kind_stream_json */ : 'MCP (Opt-in)' /* connector_kind_mcp */
}

/** The five capability dimensions (Capabilities minus the `kind` discriminant), in display order (spec §7). */
export const CAPABILITY_DIMENSIONS = [
  'structuredUsage',
  'toolGranularity',
  'reliableResult',
  'rateLimitSignal',
  'coordination',
] as const
export type CapabilityDimension = (typeof CAPABILITY_DIMENSIONS)[number]

export function dimensionLabel(dim: CapabilityDimension): string {
  switch (dim) {
    case 'structuredUsage':
      return 'Strukturierte Nutzung' // connector_dim_structured_usage
    case 'toolGranularity':
      return 'Tool-Granularität' // connector_dim_tool_granularity
    case 'reliableResult':
      return 'Verlässliches Ergebnis' // connector_dim_reliable_result
    case 'rateLimitSignal':
      return 'Rate-Limit-Signal' // connector_dim_rate_limit_signal
    case 'coordination':
      return 'Koordination' // connector_dim_coordination
  }
}

export function statusLabel(s: CapabilityStatus): string {
  switch (s) {
    case 'available':
      return 'Verfügbar'
    case 'limited':
      return 'Eingeschränkt'
    case 'unavailable':
      return 'Nicht verfügbar'
  }
}

/** The advisory preview for a kind, from GET /api/connectors (the single source, §0/§2). Null if the endpoint
 *  hasn't the descriptor → the opt-in stays fail-closed (no visible preview ⇒ no confirm). */
export function previewCapabilities(view: ConnectorsView | null, kind: ConnectorKind): Capabilities | null {
  if (view === null) return null
  return view.connectors.find((c) => c.kind === kind)?.capabilities ?? null
}

/** The B (mcp) opt-in confirm is enabled ONLY when: operator-editable ∧ risk acknowledged ∧ the advisory preview is
 *  actually loaded/visible. You cannot honestly acknowledge a risk you can't see (spec §4, tooth 3). */
export function canConfirmOptIn(editable: boolean, riskAcknowledged: boolean, previewLoaded: boolean): boolean {
  return editable && riskAcknowledged && previewLoaded
}

/** The three named risks of B — amber ATTENTION (a disclosure of a chosen thing), never error-red (spec §4.2). */
export const OPTIN_RISKS = {
  bypass: 'Umgeht die strukturierte stream-json-Ebene – geringere Signaltreue.', // connector_optin_risk_bypass
  account: 'Läuft ggf. über ein separates Konto/Provider-Setup.', // connector_optin_risk_account
  fragile: 'Fragiler: weniger verlässliche Ergebnis-/Rate-Limit-Signale.', // connector_optin_risk_fragile
} as const

export const CONNECTOR_TEXT = {
  pickerLabel: 'Connector', // connector_picker_label
  defaultNote: 'Stream-JSON ist der Standard (volle Fidelity); MCP ist ein bewusster Opt-in.', // connector_default_note
  optinTitle: 'MCP-Connector aktivieren?', // connector_optin_title
  optinIntro: 'MCP ist eine Opt-in-Alternative mit geringerer Fidelity und benanntem Risiko.', // connector_optin_intro
  previewTitle: 'Was MCP aufgibt (Vorschau):', // connector_optin_preview_title
  ack: 'Ich verstehe das Risiko und aktiviere MCP bewusst.', // connector_optin_ack
  // load-bearing, verbatim — makes the anti-injection invariant visible (§6).
  humanOnly: 'Nur ein Operator kann den Connector wechseln – nie ein Agent oder eine Kanal-Nachricht.', // connector_optin_human_only
  confirm: 'MCP aktivieren', // connector_optin_confirm
  cancel: 'Abbrechen', // connector_optin_cancel
  optinError: 'Connector-Wechsel fehlgeschlagen', // connector_optin_error
  previewLoadFailed: 'Vorschau laden fehlgeschlagen', // load_failed (reuse)
  // amber "saved ≠ active" → restart (reuse CYP-88); only on EDIT when the draft differs from the current kind.
  effectHint:
    'Gespeichert. Wirkt erst beim nächsten Start des Agenten – jetzt neu starten, damit der neue Connector zieht.', // agent_edit_effect_hint
} as const
