package com.tneff.cyppieagents.model

import kotlinx.serialization.Serializable

/**
 * CYP-798 — the **language-neutral hub-trust vocabulary** (axis a: TOFU / hub-key), shared via `:core` so Compose
 * AND web-ts speak ONE contract (pattern CYP-622 `MuxHello`). `@Serializable` → exported to the OpenAPI contract.
 *
 * **Scope (PL-ratified, §4b):** axis a only. KEPT SEPARATE from axis c (`:server` `RemoteIssuerTrustState`, issuer)
 * — never fold. The client-local TOFU key primitive (`TrustResolution` UNPINNED/PINNED_OK/KEY_CHANGED) stays in
 * `:app:shared`; only this UX vocabulary is shared.
 */

/**
 * The 5-state **client-facing** TOFU trust state (uiux2 §5b / the CYP-747 N3 model). The client DERIVES this from its
 * local axes; the wire carries the resolved state so Compose + web-ts render identically.
 *
 * **Fail-closed:** [UNKNOWN] is the default — an unknown/absent/not-yet-evaluated verdict **NEVER** renders as trusted
 * ("never green-by-default", safe-but-silent). Only [TRUSTED] is the "green" state.
 */
@Serializable
enum class HubTrustState {
    /** No trust verdict yet (default) — not-yet-evaluated / no data. Never trusted. (e.g. a network error → here, N4.) */
    UNKNOWN,

    /** A trust decision is in progress — a **distinct** state, ≠ [UNKNOWN] (PL-freeze §5b-P2). e.g. the OOB fingerprint
     *  confirm is open, awaiting the human compare. */
    PENDING,

    /** The hub key is pinned and matches — the only "green" state. */
    TRUSTED,

    /** Trust was EVALUATED and refused/aborted (see [TrustRejectReason]); terminal for this attempt. */
    REJECTED,

    /** A previously-trusted verdict can no longer be confirmed current (e.g. issuer revocation, or a non-LIVE feed) —
     *  MARKED, not silently shown fresh (safe-but-silent, cf. CYP-789). */
    STALE,
}

/**
 * A **CLOSED** enum of ONLY real trust-EVALUATION reject reasons (axis a). PL-frozen RULE (§4b): closed ·
 * trust-eval-only · N4-distinct · machine-code (never a string-match). **★ Standing rule: a NEW reason requires PL
 * RE-ratification** — else N4 tips silently. Members are grounded in the actual reject conditions:
 *
 * **Deliberately EXCLUDED** (each routes elsewhere — N4 the client MUST distinguish these, never fold in):
 *  - network error → [HubTrustState.UNKNOWN] (couldn't evaluate);
 *  - issuer revocation → [HubTrustState.STALE] (and issuer is axis c anyway);
 *  - malformed / ≠32-byte descriptor → [HubDescriptorValidity.MALFORMED] (a distinct UPSTREAM signal, not a reject).
 */
@Serializable
enum class TrustRejectReason {
    /** The presented hub static ≠ the pinned key (a TOFU mismatch, auto-detected vs the pin) ⇒ HARD BLOCK; needs OOB
     *  re-pin. (Client-local `TrustResolution.Changed` / the CYP-478 TrustChanged path.) */
    KEY_CHANGED,

    /** The operator rejected the first-use OOB fingerprint compare ("doesn't match") — nothing pinned, terminal.
     *  (The CYP-478/696 TrustRejected path.) */
    OOB_REJECTED,
}

/**
 * CYP-798 §4b (PL-ratified, BINDING) — the **separate** descriptor-validity / upstream signal, the **4th** distinct
 * N4 axis (network ≠ **malformed** ≠ reject ≠ revocation). A [MALFORMED] hub descriptor (e.g. `dhPubKey` that is not
 * base64 or ≠ 32 bytes) means the client **could not even evaluate** trust — it is NOT a [TrustRejectReason] (an
 * evaluated decision) and NOT a [HubTrustState] value. **Fail-closed:** MALFORMED is **never** trusted, and the UI
 * MUST always surface a `⚠` on malformed (the "always-on-malformed" rule). Kept as its own enum so folding can never
 * silently collapse it into a reject/trust state.
 */
@Serializable
enum class HubDescriptorValidity {
    /** The hub descriptor's key decoded to exactly 32 bytes — evaluable. */
    VALID,

    /** The descriptor key is non-base64 or ≠ 32 bytes ⇒ no fingerprint could be derived (upstream/corruption/MITM/bug).
     *  Fail-closed: never trusted; always `⚠`-surfaced. Anchored client-side by the `decodePin` seam (→ null). */
    MALFORMED,
}
