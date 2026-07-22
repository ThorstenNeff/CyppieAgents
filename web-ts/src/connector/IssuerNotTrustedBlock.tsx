// CYP-805 (S1c) — the web-ts IssuerNotTrusted TERMINAL block (axis c), the sibling of the Compose CYP-797 hard-block.
// Renders ONLY when the issuer-trust decision is `block` (issuerTrust === NOT_TRUSTED); otherwise renders nothing
// (proceed). ★ TERMINAL: there is NO retry — a rejected issuer vouch is resolved OUT-OF-BAND, never by a client re-try
// (remote-connect §HD: terminal, no-retry). Assertive a11y (role="alert") — the operator just tried to connect and the
// honest refusal must announce at once. Colour is never the sole carrier (WCAG 1.4.1): the glyph + the text carry the
// meaning. OWN token namespace `issuer-not-trusted` — NOT hub-trust-* (axis a) and NOT tier* — the three trust/tier
// axes never share a namespace.
//
// ★ Copy/glyph/tone come from the INTERIM const in issuerTrustModel.ts (uiux2 owns the final — 1-place re-point). The
// STRUCTURE here (terminal, no-retry, assertive, distinct namespace) is established and final.
import { issuerConnectDecision, ISSUER_NOT_TRUSTED_BLOCK_INTERIM, type HubIssuerTrust } from './issuerTrustModel'

export interface IssuerNotTrustedBlockProps {
  hubId: string
  /** Absent/`TRUSTED`/`REMOTE_NOT_CONFIGURED` → no block (proceed); only `NOT_TRUSTED` renders the terminal block. */
  issuerTrust?: HubIssuerTrust | null
}

export function IssuerNotTrustedBlock({ hubId, issuerTrust = null }: IssuerNotTrustedBlockProps) {
  if (issuerConnectDecision(issuerTrust) === 'proceed') return null
  const c = ISSUER_NOT_TRUSTED_BLOCK_INTERIM
  return (
    <div className="issuer-not-trusted" data-testid={`issuer.notTrusted.${hubId}`} role="alert">
      <span className="issuer-not-trusted-glyph" aria-hidden="true">
        {c.glyph}
      </span>
      <div className="issuer-not-trusted-body">
        <span className="issuer-not-trusted-title">{c.title}</span>
        <span className="issuer-not-trusted-detail">{c.detail}</span>
        <span className="issuer-not-trusted-oob">{c.oobHint}</span>
      </div>
      {/* TERMINAL — deliberately NO retry/dismiss control: the issuer vouch is resolved out-of-band, not by the client. */}
    </div>
  )
}
