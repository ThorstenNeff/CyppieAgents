// CYP-801 (N3) — the standalone per-hub trust badge, from uiux2 spec CYP-755 (0ac8215e) §1/§2. Prop-driven; renders
// the trust state of ONE hub honestly + not alarmingly (unknown/pending neutral, only rejected warns). Mirrors the
// RemoteSecurityTierBadge idiom (glyph FORM + label WORD → colour never sole; fail-closed default).
//
// ★ TWO SIGNALS, not one arm (uiux2 §4 / CYP-798 §4b): the trust PILL is always one of the 5 states (a MALFORMED
// descriptor collapses it to UNKNOWN, fail-closed — never rejected/trusted); the ⚠ UPSTREAM marker is SEPARATE, in
// its own token namespace (hub-descriptor-invalid), rendered MANDATORILY whenever the descriptor is malformed. The two
// never share the hub-trust-* namespace — conflating a descriptor error with a trust verdict is the honesty failure
// this guards against.
import { hubTrustBadgeState, hubTrustGlyphSpec, descriptorUpstreamError, HUB_DESCRIPTOR_INVALID } from './hubTrustView'
import type { HubTrustState, HubDescriptorValidity } from '../connector/hubTrustModel'

export interface HubTrustBadgeProps {
  hubId: string
  /** Fail-closed: an absent/null trust signal is UNKNOWN, never TRUSTED (§1). */
  trust?: HubTrustState | null
  /** The SEPARATE descriptor-validity axis. Default VALID = "not flagged malformed" (absence must not fabricate a ⚠);
   *  only an explicit MALFORMED raises the upstream marker + collapses the pill to UNKNOWN. */
  validity?: HubDescriptorValidity
}

export function HubTrustBadge({ hubId, trust = null, validity = 'VALID' }: HubTrustBadgeProps) {
  const v = hubTrustGlyphSpec(hubTrustBadgeState(trust, validity))
  const upstreamError = descriptorUpstreamError(validity)
  return (
    // aria-live polite: state changes are announced calmly (CYP-755 §1). The active-hub assertive escalation is a
    // placement concern (§2), wired where the badge mounts in the chrome strip, not baked into the leaf badge.
    <span className="hub-trust" data-testid={`hub.trust.${hubId}`} role="status" aria-live="polite">
      {/* the trust pill — role="img" + aria-label spells out the state (the meaning WORD, never glyph/colour alone);
          the state-discriminating anchor hub.trust.{hubId}.{state} is present for exactly the current state. */}
      <span
        className={`hub-trust-pill hub-trust-${v.presentation}`}
        data-testid={v.testId(hubId)}
        data-state={v.presentation}
        data-tone={v.tone}
        role="img"
        aria-label={v.a11yLabel}
      >
        <span className="hub-trust-glyph" aria-hidden="true">
          {v.glyph}
        </span>
        <span className="hub-trust-label">{v.label}</span>
      </span>
      {/* SEPARATE upstream-error marker — distinct namespace, mandatory on malformed (never hub-trust-*, never hidden). */}
      {upstreamError && (
        <span
          className={HUB_DESCRIPTOR_INVALID.className}
          data-testid={HUB_DESCRIPTOR_INVALID.testId(hubId)}
          role="alert"
        >
          <span className="hub-descriptor-invalid-glyph" aria-hidden="true">
            ⚠
          </span>
          <span className="hub-descriptor-invalid-text">{HUB_DESCRIPTOR_INVALID.label}</span>
        </span>
      )}
    </span>
  )
}
