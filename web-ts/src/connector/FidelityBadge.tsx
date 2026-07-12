// CYP-488 (P2-a follow) — the OBSERVED fidelity badge + capability panel at the agent header. Ported from CYP-119 §2.2
// (compact marker) / §2.3 (detail panel). Fail-closed BY ABSENCE: the badge is an exception marker — present ⇔ the
// agent's fidelity is < full OR not-yet-reported (capabilities == null). A full-fidelity agent shows NO badge (no
// false "all green" seal). This is the OBSERVED register (Agent.capabilities), spatially separate from the CYP-461
// ADVISORY pre-choice preview — never mixed. Colour never the sole carrier (glyph + label). Content-free.
import { useState } from 'react'
import type { Capabilities } from '../types/generated/contract'
import {
  CAPABILITY_DIMENSIONS,
  dimensionLabel,
  statusLabel,
  capStatusGlyph,
  badgePresent,
  activeConnectorLabel,
  FIDELITY_TEXT as T,
} from './fidelityModel'

export interface FidelityBadgeProps {
  agentId: string
  capabilities: Capabilities | null | undefined
  connectorKind?: string | null
}

export function FidelityBadge({ agentId, capabilities, connectorKind }: FidelityBadgeProps) {
  const [open, setOpen] = useState(false)

  // fail-closed by absence: full fidelity → no badge, no noise (§2.2).
  if (!badgePresent(capabilities)) return null

  const caps = capabilities ?? null
  const unknown = caps === null
  return (
    <span className="fidelity">
      <button
        type="button"
        className={`fidelity-badge${unknown ? ' fidelity-unknown' : ' fidelity-limited'}`}
        data-testid={`connector.${agentId}.fidelityBadge`}
        aria-expanded={open}
        aria-label={unknown ? T.badgeUnknown : T.badgeLimited}
        onClick={() => setOpen((o) => !o)}
      >
        <span aria-hidden="true">!</span> {unknown ? T.badgeUnknown : T.badgeLimited}
      </button>

      {open && (
        <div className="fidelity-panel" role="region" aria-label={T.panelTitle} data-testid={`connector.${agentId}.capabilityPanel`}>
          <h4>{T.panelTitle}</h4>
          {/* identity ≠ fidelity — the active connector is a separate axis (§2.3). */}
          <p className="fidelity-active" data-testid={`connector.${agentId}.activeConnector`}>
            {T.activeConnectorPrefix}: {activeConnectorLabel(connectorKind)}
          </p>
          {caps === null ? (
            // fail-closed: capabilities not reported yet — never rendered as "full" (§2.1 null row).
            <p className="fidelity-not-reported" data-testid={`connector.${agentId}.notReported`}>
              {T.notReported}
            </p>
          ) : (
            <ul className="fidelity-dims">
              {CAPABILITY_DIMENSIONS.map((dim) => (
                <li key={dim} className="fidelity-dim" data-testid={`connector.${agentId}.capability.${dim}`}>
                  <span className="fidelity-dim-label">{dimensionLabel(dim)}</span>
                  {/* status = glyph + label (+ toned class); colour never alone (§2.1). */}
                  <span
                    className={`fidelity-status fidelity-cap-${caps[dim]}`}
                    data-testid={`connector.${agentId}.capability.${dim}.status`}
                    data-status={caps[dim]}
                  >
                    <span aria-hidden="true">{capStatusGlyph(caps[dim])}</span> {statusLabel(caps[dim])}
                  </span>
                </li>
              ))}
            </ul>
          )}
          <p className="fidelity-note">{T.degradedNote}</p>
        </div>
      )}
    </span>
  )
}
