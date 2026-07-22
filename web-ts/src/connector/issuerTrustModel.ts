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

// ── COPY / TONE / GLYPH — ★ CYP-805 INTERIM, uiux2 OWNS THE FINAL (axis c terminal block ≠ the neutral trust badge).
// These placeholders let the scaffold render and let Tester2 measure the STRUCTURE now; swap 1:1 when uiux2 delivers.
// What is NOT interim (established, remote-connect §HD "terminaler WARN-Amber-Block, no-retry"): the block is TERMINAL,
// has NO retry, announces assertively (role="alert"), lives in its OWN token namespace `issuer-not-trusted`, and colour
// is never the sole carrier (glyph + text). Only the exact words / glyph / tone token are uiux2's to finalize.
export const ISSUER_NOT_TRUSTED_BLOCK_INTERIM = {
  glyph: '▲', // interim established warn glyph — uiux2 confirms the final
  title: 'Verbindung blockiert — Aussteller nicht vertraut',
  detail: 'Dieser Hub vertraut deinem Aussteller-Vouch nicht. Die Remote-Strecke bleibt aus (serverseitig durchgesetzt).',
  oobHint: 'Kläre die Aussteller-Freigabe außerhalb dieses Kanals (out-of-band).',
} as const
