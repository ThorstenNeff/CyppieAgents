// CYP-676 (Epic CYP-675, Option-A prep) — the pure honesty core for the connection-security-tier indicator, ported
// from the ratified UIUX2 spec (docs/design/cyp676-trust-delta-indicator-spec.md). Standalone + prop-driven; the
// RemoteSecurityTierBadge component draws these. Surfaces the security tier of the RUNNING connection honestly but
// not alarmingly. NOT wired into the browser-remote flow — resolving which tier the live connection has is the
// S7-gated Backend/transport seam and explicitly out of this story (§6).
//
// NAMING (spec §1): this is the `remote.security.tier*` domain — DELIBERATELY NOT `trust*`. `remote.connect.trust*`
// is the one-time pinning ceremony (OOB fingerprint); this is the PERSISTENT running-connection tier. Distinct names
// so UI/tests/copy never conflate the two.
//
// FACT ordering (source: cyp638-remote-hub-trust-boundary-one-pager.md; §4 copy Backend2-fact-checked 🟢 + PO-approved 2026-07-18):
//   • NATIVE          = GUARANTEE register: strong E2E (Noise-E2E), hub identity pinned.
//   • BROWSER_GATEWAY = ADVISORY register: documented WEAKER — the gateway sees traffic in cleartext, server-served
//                       crypto without an independent pin. NEUTRAL, not an error/alarm (spec §5).
//   • UNKNOWN         = FAIL-CLOSED: tier not yet resolved. The component NEVER defaults to NATIVE before the tier is
//                       known (no optimistic green); UNKNOWN is the honest resting state + the default (§2).
//
// COPY: FINAL (spec §4 @ d1141aff, B2-fact-checked 🟢 + PO-approved). Centralized at ONE seam (REMOTE_SECURITY_TIER_TEXT)
// so it stays the single source; the tests bind to structure/tags, not wording. Note: spec §4 also defines a
// `native_detail` string, but the ratified badge (§3) renders only the pill + the gateway disclosure line — native has
// no visible detail element — so native_detail is intentionally NOT stored here (no dead copy); if a native detail
// surface is later added, it swaps in at this seam. web-ts is DE-inline (no i18n yet), so the CMP strings.xml /
// EN-parity / shared-key concerns (spec §4 EN, §10) do not apply to this strand. The DE+EN user-doc (part a) lands
// alongside at docs/security/cyp676-trust-delta-user-doc.md.

export type RemoteSecurityTier = 'native' | 'browser-gateway' | 'unknown'

/** Presentation register — GUARANTEE (native) vs ADVISORY (gateway) vs FAIL-CLOSED (unknown); never mixed (§5). */
export type TierRegister = 'guarantee' | 'advisory' | 'fail-closed'

export interface RemoteSecurityTierView {
  tier: RemoteSecurityTier
  register: TierRegister
  /** Plain-text glyph (no emoji; JVM/desktop-safe per CYP-54). Distinct shape per tier so colour is never the sole
   *  carrier (WCAG 1.4.1): ● full / ◐ half / · pending. */
  glyph: string
  /** Stable short label (§4). */
  label: string
  /** Full a11y description that spells out the tier (§8), final §4 copy. */
  a11yLabel: string
  /** The always-visible INFO disclosure line — present ONLY for browser-gateway (§3), final §4 copy. */
  disclosure: string | null
  /** discriminating present-iff-tier anchor for the verify teeth (spec §7): remote.security.tier.<id>. */
  testId: string
}

export const REMOTE_SECURITY_TIERS: readonly RemoteSecurityTier[] = ['native', 'browser-gateway', 'unknown'] as const

/** Tag domain (spec §7) — `remote.security.tier*`, deliberately NOT the pinning `trust*` family (§1). */
export const REMOTE_SECURITY_TIER_TAGS = {
  badge: 'remote.security.tierBadge', // the pill (always present)
  disclosure: 'remote.security.tierDisclosure', // the gateway INFO line (present-iff gateway)
  tier: (id: string): string => `remote.security.tier.${id}`, // discriminating per-tier anchor
} as const

/** Map the enum value to the tag-id slug used in testIds (id ∈ {native, browserGateway, unknown}, spec §7). */
function tierId(tier: RemoteSecurityTier): string {
  return tier === 'browser-gateway' ? 'browserGateway' : tier
}

// Plain-text tier glyphs (spec §3). ● full (native, mirrors the LIVE ●+primary idiom) / ◐ half (gateway, "partial" —
// deliberately NOT ▲ which is WARN) / · pending (unknown, the established GATED/pending glyph).
const GLYPH: Record<RemoteSecurityTier, string> = { native: '●', 'browser-gateway': '◐', unknown: '·' }
const REGISTER: Record<RemoteSecurityTier, TierRegister> = {
  native: 'guarantee',
  'browser-gateway': 'advisory',
  unknown: 'fail-closed',
}

export const REMOTE_SECURITY_TIER_TEXT = {
  // Stable short labels (§4) — factual, not alarming, no unqualified "sicher"/"unsicher".
  label: {
    native: 'Ende-zu-Ende',
    'browser-gateway': 'Browser-Gateway',
    unknown: 'Wird geprüft',
  },
  // FINAL §4 copy (UIUX2 spec d1141aff, feature/CYP-676-trust-delta-indicator-spec) — Backend2 two-net fact-check 🟢
  // + PO review approved 2026-07-18. Grounded verbatim vs cyp638 (A2-hop cleartext WITH the hub · RR6 server-served
  // app without an independent pin · Präz. i release condition = self-hosted deployment you own, HUB AND GATEWAY —
  // not merely "hubs", so a self-hosted hub + foreign gateway [case b] does not read green). Honest-not-alarming.
  a11y: {
    native: 'Verbindungssicherheit: Ende-zu-Ende, direkt verschlüsselt und Hub-Identität gepinnt.',
    'browser-gateway': 'Verbindungssicherheit: Browser-Gateway — dokumentiert schwächer als die native Ende-zu-Ende-Verbindung.',
    unknown: 'Verbindungssicherheit wird ermittelt.',
  },
  gatewayDisclosure:
    'Über ein Browser-Gateway verbunden. Anders als bei der nativen Ende-zu-Ende-Verbindung endet die Verschlüsselung ' +
    'am Gateway: es sieht den Datenverkehr mit dem Hub im Klartext, und die Browser-App wird vom Server ausgeliefert ' +
    '— ohne unabhängigen Pin. Dokumentiert schwächer; vorgesehen nur für selbst gehostete Deployments, die dir gehören ' +
    '(Hub und Gateway).',
} as const

/** Derive the honest presentation for a tier. Fail-closed: an unresolved/unknown tier is NEVER rendered as NATIVE.
 *  Pure: same tier → same view; the register + level ordering are factual (see file header), not a design choice. */
export function remoteSecurityTierView(tier: RemoteSecurityTier = 'unknown'): RemoteSecurityTierView {
  return {
    tier,
    register: REGISTER[tier],
    glyph: GLYPH[tier],
    label: REMOTE_SECURITY_TIER_TEXT.label[tier],
    a11yLabel: REMOTE_SECURITY_TIER_TEXT.a11y[tier],
    disclosure: tier === 'browser-gateway' ? REMOTE_SECURITY_TIER_TEXT.gatewayDisclosure : null,
    testId: REMOTE_SECURITY_TIER_TAGS.tier(tierId(tier)),
  }
}
