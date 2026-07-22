// CYP-805 (S1c, web-ts render) — the ISSUER-trust axis (axis c: "does this hub trust YOUR issuer's vouch?"), the
// web-ts sibling of the Compose CYP-797 IssuerNotTrusted hard-block. Frozen shape PL-0107 / CYP-804.
//
// ★ AXIS DISCIPLINE — NEVER FOLD (coordinator-pinned): this is axis c and is kept SEPARATE from
// connector/hubTrustModel.ts (axis a = TOFU / hub-key trust, HubTrustState). The two carry a coincidentally-identical
// value name `TRUSTED`, but they are DISTINCT axes: `HubIssuerTrust.TRUSTED` ≠ `HubTrustState.TRUSTED` (the latter
// renders NEUTRAL, never-green DS, CYP-803). Folding them would conflate "the hub vouches for my issuer" with "I have
// pinned this hub's key" — two different trust questions.
//
// ★ WIRE SEAM — CYP-805 INTERIM (measured: `HubIssuerTrust` is NOT yet in the generated contract.ts / openapi export).
// The local type below mirrors the frozen shape 1:1. When Team-1's CYP-804 openapi export lands on develop, re-point
// consumers to `import type { HubIssuerTrust } from '../types/generated/contract'` and delete this local declaration.
// HUB_ISSUER_TRUST_VALUES is the single source — NO scattered string literals (fail-closed against drift).

// CYP-805 interim → re-point to generated contract.ts when CYP-804 openapi export lands on develop.
export type HubIssuerTrust = 'TRUSTED' | 'NOT_TRUSTED' | 'REMOTE_NOT_CONFIGURED'
export const HUB_ISSUER_TRUST_VALUES = ['TRUSTED', 'NOT_TRUSTED', 'REMOTE_NOT_CONFIGURED'] as const

export type IssuerConnectDecision = 'proceed' | 'block'

/**
 * The connect decision on the issuer-trust axis. ONLY `NOT_TRUSTED` yields the terminal `block` — the UX win: the
 * client honestly mirrors the server's already-enforced no-issuer refusal (CYP-802: the server fail-closes to an inert
 * relay, remote stays OFF, server-side; this render is UX-COMPLETENESS, not the security gate). Absent (unknown),
 * `TRUSTED`, and `REMOTE_NOT_CONFIGURED` all `proceed` — there is nothing to block. Absent → proceed is safe precisely
 * because the SERVER, not this render, enforces the gate; the client never grants the remote strecke.
 */
export function issuerConnectDecision(issuerTrust: HubIssuerTrust | null | undefined): IssuerConnectDecision {
  return issuerTrust === 'NOT_TRUSTED' ? 'block' : 'proceed'
}

// ── COPY / TONE / GLYPH — ★ FINAL (uiux2 CYP-805 UX-spec `docs/design/cyp805-issuer-not-trusted-block-ux-spec.md`,
// wortgleich zu Team-1 CYP-747 §5-C2 — cross-surface reuse, no divergent second wording). WARN-amber: IssuerNotTrusted
// is a PROTECTIVE fail-closed refusal, NOT a defect → never error-red/⚠ ("broken", that's axis-b AuthRejected) and never
// neutral (that's the axis-a trust badge, CYP-803). The terminal HARD-block hardness is carried by the STRUCTURE (no
// retry, assertive announce, OOB text-hint), NOT by the tone. Glyph ▲ = the web-ts WARN glyph (NOT ⚠ = ERROR; NOT the
// axis-a circle glyphs ◯◔●⊘◑), aria-hidden — the TEXT carries the meaning (WCAG 1.4.1).
//
// ★★ SECURITY (load-bearing, spec §4): the wire verdict carries a self-asserted `issuer: String?` — the CLAIMED id of a
// NOT-trusted peer. It is NEVER interpolated into visible copy: rendering an unverified, self-asserted name as fact is a
// spoofing surface. The field is for state/logging/testid only (parity with Compose, which also does not display it).
export const ISSUER_NOT_TRUSTED_BLOCK_COPY = {
  glyph: '▲',
  title: 'Verbindung angehalten',
  detail:
    'Dieser Hub ist registriert, aber es ist kein vertrauenswürdiger Aussteller an ihm etabliert. Ohne etabliertes Aussteller-Vertrauen wird keine Operator-Berechtigung erteilt.',
  oobHint:
    'Ein vertrauenswürdiger Aussteller wird außerhalb der App am Hub etabliert (durch den Betreiber/PO). Danach erneut verbinden.',
  a11yLabel:
    'Verbindung angehalten: kein vertrauenswürdiger Aussteller am Hub etabliert. Keine Operator-Berechtigung. Aussteller-Vertrauen wird außerhalb der App etabliert; danach erneut verbinden.',
} as const
