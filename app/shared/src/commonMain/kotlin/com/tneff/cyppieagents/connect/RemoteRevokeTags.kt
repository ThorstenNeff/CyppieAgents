package com.tneff.cyppieagents.connect

/**
 * CYP-479 — testTag contract for the remote-session revocation surface (CYP-480 Fläche ③, `remote.revoke.*`).
 * Sibling to [RemoteConnectTags] (`remote.connect.*`). DS-frozen + PO-gegenchecked (0 collision @ develop).
 * **Shared API with QA (CYP-7) — coordinate via the PO.**
 *
 * Honesty (HF, guaranteed-vs-advisory): `END`/`CONFIRM` drive a **guaranteed local teardown** of THIS
 * connection (reuse `RemoteHubSession.close()` via `HubConnectViewModel.backToHubList()`); `SCOPE_NOTE` carries
 * the "no global revoke" truth; `TTL_HINT` is **seam-gated, absent** until an Operator-Session-TTL exists
 * (③a, CYP-459) — never an invented expiry (`null≠0`).
 */
object RemoteRevokeTags {
    /** "End remote session" control (§4.1). */
    const val END = "remote.revoke.end"
    /** Destructive-confirm dialog (house scaffold). */
    const val CONFIRM = "remote.revoke.confirm"
    /** "Only this connection on this device — no global revoke" (advisory, HF/③b). */
    const val SCOPE_NOTE = "remote.revoke.scopeNote"
    /** Advisory ≤TTL — **seam-gated, absent** without an Operator-Session-TTL (③a/CYP-459, `null≠0`). */
    const val TTL_HINT = "remote.revoke.ttlHint"
    /** Result "remote session ended" → LOST → hub list. */
    const val ENDED = "remote.revoke.ended"
}
