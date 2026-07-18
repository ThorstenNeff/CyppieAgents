package com.tneff.cyppieagents.connect

/**
 * CYP-471 — testTag contract for the remote (Noise-E2E) connect states, in the `remote.connect.*` area (CYP-429
 * §7 naming; sibling to `OperatorAuthTags`' `remote.authStep.*`). **Shared API with QA (CYP-7) — coordinate via the
 * PO** (like the CYP-460 tags). `error(<cause>)` carries the fail-closed taxonomy; `trustChanged`/`authRejected`
 * are terminal causes (no retry), distinct from the retryable relay/hub/handshake causes.
 */
object RemoteConnectTags {
    const val RELAY_DIALING = "remote.connect.relayDialing"
    const val E2E_HANDSHAKE = "remote.connect.e2eHandshake"
    const val TRUST_CHECK = "remote.connect.trustCheck"
    /** CYP-475 §-QA①: the provisional-trust disclosure at trust-check while real pinning (dhPubKey) is RR5-downstream. */
    const val TRUST_PROVISIONAL = "remote.connect.trustProvisional"
    const val AUTHENTICATING = "remote.connect.authenticating"
    const val CONNECTED = "remote.connect.connected"
    /** CYP-523: Seq-B forward action from the CONNECTED state → the workspace („Loslegen"). Additive (shared API w/ QA via PO). */
    const val TO_WORKSPACE = "remote.connect.toWorkspace"
    /** The single workspace-scoped relay-drop / reconnecting surface (H4) — not N per-agent chips. */
    const val RELAY_DROP = "remote.relayDrop"
    /** Retry affordance — present ONLY for retryable transport failures, NEVER for a terminal trust-changed/auth-rejected. */
    const val RETRY = "remote.connect.retry"

    // CYP-595 (UIUX expiry-subset) — the enroll finalize window expired (RR3 receive timed out): the shown codes are
    // stale; reconnect for fresh ones. codesWindowExpired = Assertive; codesReconnect reuses the connectRemote re-TOFU.
    const val CODES_WINDOW_EXPIRED = "remote.connect.codesWindowExpired"
    const val CODES_RECONNECT = "remote.connect.codesReconnect"

    // CYP-482 S-B (§3) — the FirstUse OOB-fingerprint-confirm screen (frozen CYP-480 §① contract; shared API w/ QA).
    /** FirstUse OOB-confirm screen container (§2.1) — the mandatory-blocking First-Use gate. */
    const val TRUST_FIRST = "remote.connect.trustFirst"
    /** Fingerprint block container (only ever a REAL derived fingerprint, never a placeholder — HB). */
    const val TRUST_FINGERPRINT = "remote.connect.trustFingerprint"
    /** PGP word sequence — primary, human OOB compare (11 numbered even/odd tokens). */
    const val TRUST_WORDLIST = "remote.connect.trustWordlist"
    /** Hex fingerprint — secondary, copyable. */
    const val TRUST_HEX = "remote.connect.trustHex"
    /** QR (scan path) — the `cyppie-hub-key:` payload. */
    const val TRUST_QR = "remote.connect.trustQr"
    const val TRUST_OOB = "remote.connect.trustOob"
    /** OOB compare against the hub console (Option X). */
    const val TRUST_OOB_CONSOLE = "remote.connect.trustOobConsole"
    /** Neutral "identity pinned" indicator (§2.2) — never green. */
    const val TRUST_PINNED = "remote.connect.trustPinned"
    /** "Matches — pin it" → approve() → pin → continue to AUTHENTICATING. */
    const val TRUST_CONFIRM = "remote.connect.trustConfirm"
    /** "Doesn't match — abort" → reject() → **fail-closed**, no pin, teardown (HB/HC). */
    const val TRUST_REJECT = "remote.connect.trustReject"
    /** Result "not connected — identity not confirmed" (the Rejected state). */
    const val TRUST_ABORTED = "remote.connect.trustAborted"
    /** Purely informational "re-pin only OOB" hint at the TrustChanged alarm (HC/①b) — NOT an action button. */
    const val TRUST_CHANGED_REPIN = "remote.connect.trustChangedRepin"

    /**
     * `remote.connect.error.<cause>`, cause ∈ relayUnreachable / hubOffline / handshakeFailed / trustChanged /
     * trustRejected (CYP-478/696, terminal first-use OOB decline) /
     * authRejected / deviceNotEnrolled / operatorUvFailed / enrollCodesUnavailable (CYP-525 ①, GE8 — retryable
     * delivery failure, never authRejected).
     */
    fun error(cause: String) = "remote.connect.error.$cause"
}
