// CYP-488 (P2-a follow) — the OBSERVED fidelity honesty core (pure). Ported from CYP-119 §2 (connector-capabilities)
// / CYP-461 §7. This is the OBSERVED register (Agent.capabilities from the connector) — spatially separate from the
// CYP-461 ADVISORY pre-choice preview (never mixed). Reuses the ONE capability helper source (connectorModel).
import type { Capabilities } from '../types/generated/contract'
import { CAPABILITY_DIMENSIONS, type CapabilityStatus } from './connectorModel'
export { CAPABILITY_DIMENSIONS, dimensionLabel, statusLabel, type CapabilityStatus, type CapabilityDimension } from './connectorModel'

/** Degraded = ANY dimension is not `available`. */
export function isDegraded(caps: Capabilities): boolean {
  return CAPABILITY_DIMENSIONS.some((d) => caps[d] !== 'available')
}

/** The badge is an EXCEPTION marker — present ⇔ fidelity < full OR not-yet-reported (fail-closed by absence, §2.2).
 *  A full-fidelity agent (all dims available) shows NO badge — no noise, no false "all green" seal. `null` = not
 *  reported ⇒ badge present (never silently "full"). */
export function badgePresent(caps: Capabilities | null | undefined): boolean {
  return caps == null || isDegraded(caps)
}

/** The per-status glyph (plain text, JVM-safe; colour never alone — the glyph + label carry it). */
export function capStatusGlyph(s: CapabilityStatus): string {
  switch (s) {
    case 'available':
      return '✓'
    case 'limited':
      return '!'
    case 'unavailable':
      return '○'
  }
}

export const FIDELITY_TEXT = {
  badgeLimited: 'Eingeschränkt', // connector_fidelity_badge
  badgeUnknown: 'Fähigkeiten noch nicht gemeldet', // connector_fidelity_unknown (null = fail-closed, not "full")
  panelTitle: 'Connector-Fähigkeiten', // connector_capabilities_title
  activeConnectorPrefix: 'Aktiver Connector', // connector_active ("Aktiver Connector: %1$s")
  degradedNote: 'Eingeschränkte oder nicht verfügbare Fähigkeiten werden ehrlich markiert – nie vorgetäuscht.', // connector_degraded_note
  notReported: 'Fähigkeiten wurden vom Connector noch nicht gemeldet.', // caps==null panel body (fail-closed)
} as const

/** The active-connector identity label (identity ≠ fidelity) — the human connector kind, or "—" when unknown. */
export function activeConnectorLabel(kind: string | null | undefined): string {
  if (kind === 'stream_json') return 'Stream-JSON'
  if (kind === 'mcp') return 'MCP'
  return '—'
}
