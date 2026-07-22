import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

// Cross-lang trust-render PARITY (uiux2 N3 / PL-0107). Web-ts-only, hermetic (reads source files) — NO Gradle/skiko,
// so no runComposeUiTest-hang. This is the CYP-798-class co-drift catch: assertions the per-surface teeth CANNOT make.
//
// ★ RECONCILED after CYP-805 + CYP-802-③ landed (develop f6c850cd). The axis-c issuer render + its honesty are now
// COVERED per-surface — this file must NOT duplicate them:
//   • Compose mount/visibility: `Cyp747IssuerNotTrustedRenderTest` (runComposeUiTest mounts the REAL RemoteConnectingView
//     at IssuerNotTrusted+LOST → tag visible + fail-closed + Assertive; mutation-proven).
//   • full produce chain → terminal state: `Cyp802IssuerChainE2eTest` (CP wire → toClient → DescriptorIssuerCheck →
//     terminal IssuerNotTrusted STATE). Produce + render meet at RemoteSessionState ⇒ visibility transitively proven.
//   • axis-c≠a at the render seam: `IssuerNotTrustedBlock.f3Honesty.test.tsx` + structural `Cyp443TrustAxisSeparationGuardTest`.
// The 3 earlier RED-until-CYP-805 placeholders are RETIRED (the render landed, covered by the above).
//
// ★ THE ONE co-drift gap this file closes: the issuer testids are HAND-MATCHED string literals across two languages —
// Compose `RemoteConnectTags` (const + `error("issuerNotTrusted")` in the render arm) vs web-ts `IssuerNotTrustedBlock.tsx`
// `data-testid`. CYP-805 `5258d001` intended "same tags Desktop+Browser" but NOTHING enforces it: a one-sided rename
// (the tag + its own surface's test drift together) stays green while cross-surface Maestro/parity breaks silently.
//
// ★ NON-VACUITY (PL requirement): BOTH sides are read from the REAL source (like Cyp443 reads :core), NEVER a hardcoded
// copy of "issuerNotTrusted" — else this test would itself be a driftable twin a rename could not redden. A rename on
// EITHER surface (const, the render arm's cause literal, or the web-ts data-testid) makes the two source-read sets differ → RED.
//
// axis-a (`HubTrustState` neutral-on-surface) cross-lang parity is CARRY-FORWARD: the Compose HubTrustState badge is
// UNBUILT (no Composable consumes it; PL deferred it, LOW-prio ticket). web-ts axis-a render honesty is covered by
// CYP-801 + CYP-803 (`hubTrustTrustedTone.honesty.test.ts`). No parity is asserted here against a non-existent surface.

const REPO = resolve(process.cwd(), '..') // process.cwd() == web-ts/ ; the repo root holds app/, web-ts/, contract/…
const read = (rel: string): string => readFileSync(resolve(REPO, rel), 'utf8')
const one = (re: RegExp, src: string, what: string): string => {
  const m = re.exec(src)
  if (!m || !m[1]) throw new Error(`cross-lang parity extraction failed: ${what} (regex ${re}) — source shape changed`)
  return m[1]
}

describe('cross-lang issuer-render testid CONTRACT parity (Compose source ≡ web-ts source, co-drift-catching)', () => {
  // ── Compose side: all three inputs read from real source (const + error() prefix + the render arm's cause) ──────────
  const tagsSrc = read('app/shared/src/commonMain/kotlin/com/tneff/cyppieagents/connect/RemoteConnectTags.kt')
  const errorPrefix = one(/fun error\(cause: String\)\s*=\s*"([^"$]*)\$cause"/, tagsSrc, 'RemoteConnectTags.error() prefix')
  const issuerOobConst = one(/const val ISSUER_OOB\s*=\s*"([^"]+)"/, tagsSrc, 'RemoteConnectTags.ISSUER_OOB')

  const renderSrc = read('app/shared/src/commonMain/kotlin/com/tneff/cyppieagents/connect/HubConnectSelection.kt')
  const armStart = renderSrc.indexOf('is RemoteFailure.IssuerNotTrusted ->')
  // Bound the arm to the NEXT `is RemoteFailure.` branch (fallback: a generous window) so the whole IssuerNotTrusted
  // block is captured — including its OOB node — while the first `error("…")` match stays this arm's own cause.
  const nextArm = renderSrc.indexOf('is RemoteFailure.', armStart + 20)
  const arm = renderSrc.slice(armStart, nextArm > armStart ? nextArm : armStart + 1600)
  const issuerCause = one(/RemoteConnectTags\.error\("([^"]+)"\)/, arm, 'IssuerNotTrusted arm error(cause) literal')
  const armUsesOobConst = /RemoteConnectTags\.ISSUER_OOB/.test(arm)

  const composeIssuerTags = new Set([errorPrefix + issuerCause, issuerOobConst])

  // ── web-ts side: the block's own data-testid literals, read from source ─────────────────────────────────────────
  const blockSrc = read('web-ts/src/connector/IssuerNotTrustedBlock.tsx')
  const webIssuerTags = new Set([...blockSrc.matchAll(/data-testid="([^"]+)"/g)].map((m) => m[1]))

  it('non-vacuity: the extractions found real, non-empty tags on both surfaces (armStart present, OOB via the const)', () => {
    expect(armStart).toBeGreaterThanOrEqual(0)
    expect(armUsesOobConst).toBe(true) // the arm tags OOB via RemoteConnectTags.ISSUER_OOB (not an inline literal)
    for (const t of composeIssuerTags) expect(t.startsWith('remote.connect.')).toBe(true)
    expect(composeIssuerTags.size).toBe(2)
    expect(webIssuerTags.size).toBe(2)
  })

  it('★ Compose-source issuer testids ≡ web-ts-source issuer testids (a one-sided rename on EITHER surface reds)', () => {
    // Both sets are derived by READING the real source of each surface — neither is a hardcoded copy. This is the
    // enforcer 5258d001 intended but never wrote: Desktop and Browser resolve to the SAME issuer tags, or this reds.
    expect(webIssuerTags).toEqual(composeIssuerTags)
  })
})
