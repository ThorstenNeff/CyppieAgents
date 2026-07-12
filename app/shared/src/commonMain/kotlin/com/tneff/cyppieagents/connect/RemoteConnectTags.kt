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
    /** The single workspace-scoped relay-drop / reconnecting surface (H4) — not N per-agent chips. */
    const val RELAY_DROP = "remote.relayDrop"
    /** Retry affordance — present ONLY for retryable transport failures, NEVER for a terminal trust-changed/auth-rejected. */
    const val RETRY = "remote.connect.retry"

    /** `remote.connect.error.<cause>`, cause ∈ relayUnreachable / hubOffline / handshakeFailed / trustChanged / authRejected. */
    fun error(cause: String) = "remote.connect.error.$cause"
}
