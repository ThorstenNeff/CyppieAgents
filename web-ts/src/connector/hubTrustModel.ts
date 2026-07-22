// CYP-798 — the language-neutral hub-trust VOCABULARY (axis a: TOFU / hub-key), mirroring :core/model/HubTrust.kt so
// web-ts and Compose speak ONE contract. String-literal unions (the web-ts idiom); a contract-parity honesty test
// binds them to the exported OpenAPI enums (hubTrustContractParity.honesty.test.ts). Kept SEPARATE from the issuer
// axis c (never folded).

/**
 * The 5-state client-facing TOFU trust state (uiux2 §5b / the CYP-747 N3 model). Fail-closed: [HUB_TRUST_STATE_DEFAULT]
 * is UNKNOWN — an unknown/absent/not-yet-evaluated verdict NEVER renders as trusted ("never green-by-default").
 */
export type HubTrustState = 'UNKNOWN' | 'PENDING' | 'TRUSTED' | 'REJECTED' | 'STALE'
export const HUB_TRUST_STATES: readonly HubTrustState[] = ['UNKNOWN', 'PENDING', 'TRUSTED', 'REJECTED', 'STALE'] as const
/** Fail-closed default — never green-by-default. */
export const HUB_TRUST_STATE_DEFAULT: HubTrustState = 'UNKNOWN'

/**
 * A CLOSED set of real trust-EVALUATION reject reasons (axis a). PL-frozen RULE: closed · trust-eval-only ·
 * N4-distinct · machine-code. ★ A NEW reason requires PL re-ratification (else N4 tips silently). Excluded: network
 * (→ UNKNOWN), revocation (→ STALE), malformed (→ HubDescriptorValidity.MALFORMED, an upstream error, not a reject).
 */
export type TrustRejectReason = 'KEY_CHANGED' | 'OOB_REJECTED'
export const TRUST_REJECT_REASONS: readonly TrustRejectReason[] = ['KEY_CHANGED', 'OOB_REJECTED'] as const

/**
 * N4's 4th axis — the SEPARATE descriptor-validity / upstream signal (network ≠ malformed ≠ reject ≠ revocation). A
 * [MALFORMED] descriptor (non-base64 / ≠32-byte `dhPubKey`) means trust could NOT be evaluated — never a reject, never
 * trusted; the UI must always surface a ⚠ on malformed. Mirrors :core `HubDescriptorValidity`.
 */
export type HubDescriptorValidity = 'VALID' | 'MALFORMED'
export const HUB_DESCRIPTOR_VALIDITIES: readonly HubDescriptorValidity[] = ['VALID', 'MALFORMED'] as const
