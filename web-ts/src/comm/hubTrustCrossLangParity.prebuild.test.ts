import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { hubTrustGlyphSpec } from './hubTrustView'
import { HUB_TRUST_STATE_DEFAULT } from '../connector/hubTrustModel'

// CYP-804/805/③ — **cross-lang render-parity PREBUILD** for the two trust axes (uiux2 N3 + PL-0107). Built NOW against
// the FROZEN shape (CYP-804-② `HubIssuerTrust` @ develop 9cec7cfc), RED-until-render via `fail()` (NOT `it.skip`/
// `@Ignore`, so it can never pass as an unwired no-op), then sharpened when the renders land: CYP-805 = web-ts axis-c
// issuer render · ③ = Compose axis-a N3 render. This is the same "prebuild against the frozen freeze" pattern as the
// CYP-798 cross-lang parity harness.
//
// ★ NON-DUPLICATION (what this file deliberately does NOT re-assert — the frozen shape is already covered):
//  • axis-a web-ts RENDER honesty (TRUSTED neutral, on-surface-not-primary, on-surface≠on-surface-variant) — COVERED by
//    CYP-801 `hubTrustView.test.ts` + CYP-803 `hubTrustTrustedTone.honesty.test.ts` (§6 Tooth 10 + over-neutralization).
//  • the two-TRUSTED CONTRACT distinctness (4 distinct standalone `components/schemas`, no name collision) — COVERED by
//    `server/.../Cyp798StandaloneEnumExportTest.standaloneExport_introducesNoNameCollision`.
//  • the `issuerTrust` WIRE shape (absent-when-null, plain string enum) — COVERED by `server/.../Cyp804IssuerTrustWireTest`.
// This file covers ONLY the not-yet-covered legs: the axis-c web-ts RENDER, and the cross-surface + cross-axis RENDER
// parity. The Compose ③ leg is the PAIRED Kotlin sibling (app/shared jvmTest) — web-ts cannot import a Composable; both
// bind the SAME [CRITERIA] reference below.
//
// PL-0107: TWO states literally named "TRUSTED" — axis-a `HubTrustState.TRUSTED` (the N3 hub-key TOFU state, now
// NEUTRAL) and axis-c `HubIssuerTrust.TRUSTED` (issuer posture) — must stay DISTINCT in type/namespace/render. The
// contract keeps the TYPES distinct (Cyp798); this prebuild is where the RENDER-namespace distinctness will be pinned.

// ── the FROZEN cross-lang render reference (the single parity contract both surfaces bind to) ─────────────────────
const CRITERIA = {
  /** axis-a TRUSTED render tone (CYP-803 ruling): NEUTRAL, never a 'positive'/green affirming accent. */
  axisA_trustedTone: 'neutral',
  /** axis-a TRUSTED glyph token: FULL-emphasis evaluated-neutral `on-surface` — distinct from absence's muted tone. */
  axisA_trustedGlyphToken: 'on-surface',
  /** absence (unknown/pending) glyph token: MUTED `on-surface-variant`. TRUSTED must NOT collapse into this
   *  (over-neutralization, forbidden by CYP-803 §6 Tooth 10-b). The two-neutrals distinction is a honesty invariant. */
  absenceGlyphToken: 'on-surface-variant',
  /** fail-closed default for BOTH surfaces: absent/unknown ⇒ UNKNOWN, never an optimistic TRUSTED. */
  failClosedDefault: 'UNKNOWN',
  /** malformed is a SEPARATE upstream signal in its OWN namespace, never folded into a hub-trust-* state. */
  malformedNamespacePrefix: 'hub-descriptor-invalid',
  /** axis-a TRUSTED render anchor (present-iff-state testid). axis-c TRUSTED render must NOT reuse this namespace. */
  axisA_trustedTestId: (hubId: string) => `hub.trust.${hubId}.trusted`,
  /** the frozen axis-c posture states (CYP-804 `HubIssuerTrust`), the shape CYP-805's render must bind to. */
  axisC_states: ['TRUSTED', 'NOT_TRUSTED', 'REMOTE_NOT_CONFIGURED'] as const,
} as const

describe('CYP-804/805/③ cross-lang trust render parity — GREEN now (frozen shape + parity-rule anchors)', () => {
  it('★ axis-c frozen contract (web-ts-consumed openapi.json) is the exact 3-state HubIssuerTrust shape CYP-805 binds to', () => {
    // The web-ts SIDE of the contract (Cyp798 pins the Kotlin export; this pins what web-ts actually consumes). A
    // regeneration drift that dropped/renamed a posture state — so CYP-805's render binds to a wrong shape — REDs here.
    const openapi = JSON.parse(readFileSync(resolve(process.cwd(), 'contract/openapi.json'), 'utf8'))
    const schema = openapi.components?.schemas?.HubIssuerTrust
    expect(schema, 'HubIssuerTrust MUST be a named component/schema web-ts can consume for the axis-c render').toBeTruthy()
    expect(schema.type).toBe('string')
    expect(schema.enum).toEqual([...CRITERIA.axisC_states])
  })

  it('★ parity-rule non-vacuity: the two-neutrals + cross-axis-namespace rules are well-formed (so the RED-until teeth cannot be vacuous)', () => {
    // TRUSTED's full-emphasis neutral is DISTINCT from absence's muted neutral — else "both neutral" would be a
    // meaningless parity (the over-neutralization CYP-803 forbids).
    expect(CRITERIA.axisA_trustedGlyphToken).not.toBe(CRITERIA.absenceGlyphToken)
    expect(CRITERIA.axisA_trustedTone).toBe('neutral')
    // axis-a TRUSTED render namespace is the hub-trust-* family; axis-c must land OUTSIDE it (PL-0107). Pin that the
    // reference namespace is what we think it is, so the RED-until cross-axis tooth compares against the real anchor.
    expect(CRITERIA.axisA_trustedTestId('h')).toBe('hub.trust.h.trusted')
    expect(CRITERIA.failClosedDefault).toBe('UNKNOWN')
  })

  it('parity BASELINE anchor: the landed axis-a reference still emits the criteria tone (baseline validity, not a re-test of render honesty)', () => {
    // NOT a duplicate of CYP-801/803 (those pin the render's honesty). This pins the cross-lang BASELINE: if web-ts
    // axis-a ever drifts off `neutral`, the Compose ③ leg would be compared to a WRONG reference — catch it here first.
    expect(hubTrustGlyphSpec('TRUSTED').tone).toBe(CRITERIA.axisA_trustedTone)
    expect(HUB_TRUST_STATE_DEFAULT).toBe(CRITERIA.failClosedDefault)
  })
})

describe('CYP-805 axis-c web-ts issuer render — RED-until-wired (fail(), not skipped)', () => {
  const notWired = (what: string): never => {
    throw new Error(
      `CYP-805 web-ts axis-c issuer render not yet landed — cannot evaluate ${what}. No web-ts source consumes ` +
        `HubIssuerTrust yet (only the generated contract does). This gate is intentionally RED until the render is ` +
        `wired (NOT skipped, so it can never green as an unwired no-op). SHARPEN on land: replace this fail() with the ` +
        `real assertion below.`,
    )
  }

  it('★ axis-c renders {TRUSTED,NOT_TRUSTED,REMOTE_NOT_CONFIGURED} present-iff-state, distinct per posture (no fold)', () => {
    // ON LAND: render the issuer badge per posture; assert a distinct present-iff-state anchor per member of
    // CRITERIA.axisC_states — NOT_TRUSTED (the load-bearing owned-but-issuer-not-trusted → terminal IssuerNotTrusted)
    // must be its OWN render, never folded into REMOTE_NOT_CONFIGURED or a network/offline state.
    notWired('the axis-c posture render')
  })

  it('★ PL-0107 cross-axis: axis-c `HubIssuerTrust.TRUSTED` render namespace ≠ axis-a `hub.trust.{id}.trusted`', () => {
    // ON LAND: assert the axis-c TRUSTED render testId/class does NOT reuse the axis-a hub-trust-* namespace
    // (CRITERIA.axisA_trustedTestId) — the two same-named "TRUSTED" must not conflate at the render layer.
    notWired('the cross-axis render-namespace distinctness')
  })

  it('★ axis-c honest tone: issuer TRUSTED is not an over-claiming green accent; NOT_TRUSTED is a terminal (no in-app grant) cue, not an alarm-for-a-transient', () => {
    // ON LAND: axis-c tone honesty per the issuer §5-C2 model (recovery is OOB-only for NOT_TRUSTED).
    notWired('the axis-c tone honesty')
  })
})
