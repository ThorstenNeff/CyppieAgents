// CYP-805 (S1c) — the web-ts IssuerNotTrusted TERMINAL block (axis c), the sibling of the Compose CYP-797 hard-block.
// Renders ONLY when the issuer-trust decision is `block` (issuerTrust === NOT_TRUSTED); otherwise renders nothing
// (proceed). ★ TERMINAL: there is NO retry — a rejected issuer vouch is resolved OUT-OF-BAND, never by a client re-try
// (remote-connect §HD: terminal, no-retry). Assertive a11y (role="alert") — the operator just tried to connect and the
// honest refusal must announce at once. Colour is never the sole carrier (WCAG 1.4.1): the glyph + the text carry the
// meaning. OWN token namespace `issuer-not-trusted` — NOT hub-trust-* (axis a) and NOT tier* — the three trust/tier
// axes never share a namespace.
//
// ★ Copy/glyph/tone come from ISSUER_NOT_TRUSTED_BLOCK_COPY (uiux2 CYP-805 spec, final). The STRUCTURE here (terminal,
// no-retry, assertive, distinct namespace) is established and final. The container carries the full a11y description
// (spec §5) as aria-label so the assertive announcement is the clean spoken sentence, not the visual concatenation.
import { issuerConnectDecision, ISSUER_NOT_TRUSTED_BLOCK_COPY, type HubIssuerTrust } from './issuerTrustModel'

export interface IssuerNotTrustedBlockProps {
  /** Absent/`TRUSTED`/`REMOTE_NOT_CONFIGURED` → no block (proceed); only `NOT_TRUSTED` renders the terminal block. */
  issuerTrust?: HubIssuerTrust | null
}

// ★ testids = Compose parity (spec §5 / RemoteConnectTags), NOT per-hub: the remote-connect progression is a SINGLE
// connection at a time (one hub being connected), so no `{hubId}` suffix — unlike the axis-a per-hub descriptor badge
// `hub.trust.{hubId}`. Same tags on Desktop + Browser (cross-surface test parity, matches the wortgleiche copy).
export function IssuerNotTrustedBlock({ issuerTrust = null }: IssuerNotTrustedBlockProps) {
  if (issuerConnectDecision(issuerTrust) === 'proceed') return null
  const c = ISSUER_NOT_TRUSTED_BLOCK_COPY
  return (
    <div
      className="issuer-not-trusted"
      data-testid="remote.connect.error.issuerNotTrusted"
      role="alert"
      aria-live="assertive"
      aria-label={c.a11yLabel}
    >
      <span className="issuer-not-trusted-glyph" aria-hidden="true">
        {c.glyph}
      </span>
      <div className="issuer-not-trusted-body">
        <span className="issuer-not-trusted-title">{c.title}</span>
        <span className="issuer-not-trusted-detail">{c.detail}</span>
        {/* OOB recovery is a TEXT hint on its own line — NOT a button (issuer decisions are the PO/OOB boundary). */}
        <span className="issuer-not-trusted-oob" data-testid="remote.connect.issuerOob">
          {c.oobHint}
        </span>
      </div>
      {/* TERMINAL — deliberately NO retry/dismiss control: the issuer vouch is resolved out-of-band, not by the client. */}
    </div>
  )
}
