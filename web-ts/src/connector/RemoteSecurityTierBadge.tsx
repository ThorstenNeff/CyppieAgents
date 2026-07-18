// CYP-676 (Epic CYP-675, Option-A prep) — the standalone connection-security-tier badge, ported from the ratified
// UIUX2 spec (docs/design/cyp676-trust-delta-indicator-spec.md §3/§5/§6/§8). Prop-driven (`tier`), renders the
// security tier of the RUNNING connection honestly but NOT alarmingly:
//   • compact PILL (mirrors the web-ts pill idiom, WindowBadge/CapacityPill — web-ts has no shared CMP `Pill`);
//   • non-colour signal = leading tier glyph (plain text, no emoji) + label text → colour is never the sole carrier;
//   • BROWSER_GATEWAY carries an ALWAYS-VISIBLE INFO disclosure line (never tap-to-reveal — hiding a downgrade would
//     be soft-downplaying); the weakness is carried by copy + the persistent line, NOT by an alarm colour (§5);
//   • FAIL-CLOSED default: an unresolved tier renders UNKNOWN, NEVER NATIVE (no optimistic green).
//
// NOT wired into the browser-remote flow — mapping live-connection → tier is the S7-gated Backend/transport seam and
// out of this story (§6). Tags are the `remote.security.tier*` domain (spec §7), NOT the pinning `trust*` family (§1).
// The §4 security-fact copy (gateway disclosure / comparative a11y) is FINAL (spec d1141aff, B2-fact-checked 🟢 +
// PO-approved) and centralized in remoteSecurityTierModel. Structure + tone here are final.
import { remoteSecurityTierView, REMOTE_SECURITY_TIER_TAGS as T, type RemoteSecurityTier } from './remoteSecurityTierModel'

export interface RemoteSecurityTierBadgeProps {
  /** Fail-closed default — an unresolved/unmounted-before-resolution tier is UNKNOWN, never NATIVE (§2/§6). */
  tier?: RemoteSecurityTier
}

export function RemoteSecurityTierBadge({ tier = 'unknown' }: RemoteSecurityTierBadgeProps) {
  const v = remoteSecurityTierView(tier)
  return (
    <span className="rst" data-testid={T.badge}>
      {/* the pill — role="img" + aria-label spells out the tier (mirrors CMP mergeDescendants+contentDescription, §8);
          the tier-discriminating anchor (remote.security.tier.<id>) is present for exactly the current tier. */}
      <span
        className={`rst-pill rst-${v.tier}`}
        data-testid={v.testId}
        data-tier={v.tier}
        data-register={v.register}
        role="img"
        aria-label={v.a11yLabel}
      >
        <span className="rst-glyph" aria-hidden="true">
          {v.glyph}
        </span>
        <span className="rst-label">{v.label}</span>
      </span>
      {/* gateway only: the caveat is shown OPENLY (always-visible INFO line), never behind a tap (§3). */}
      {v.disclosure !== null && (
        <span className="rst-disclosure" data-testid={T.disclosure} role="note">
          <span className="rst-disclosure-glyph" aria-hidden="true">
            i
          </span>
          <span className="rst-disclosure-text">{v.disclosure}</span>
        </span>
      )}
    </span>
  )
}
