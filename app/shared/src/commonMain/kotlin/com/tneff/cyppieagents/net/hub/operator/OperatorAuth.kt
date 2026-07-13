package com.tneff.cyppieagents.net.hub.operator

import com.tneff.cyppieagents.operator.ed25519PublicKeyToRaw
import com.tneff.cyppieagents.operator.operatorAuthChallenge

/**
 * CYP-443 Slice 2 — the **transport-independent** operator-auth seams + PoP-result taxonomy. Builds the PoP; the
 * tunnel send + hub-grant round-trip (the `OperatorAuthenticator` that plugs into `RemoteHubSession`) is the
 * transport-dependent wiring that waits on the RR5 decision. All fail-closed.
 *
 * **UIUX grounding (PO):** the operator-facing PIN/fingerprint UX is UIUX's Desktop-Remote-Operator-UX-Spec — this
 * layer is UI-agnostic. The one honesty rail encoded here: a **local user-verification failure (wrong PIN /
 * cancelled) is RETRYABLE and distinct from a hub terminal reject** ([PopResult.UvFailed] vs the later
 * `OperatorAuthOutcome.HubRejected`) — never conflate "you typed the wrong PIN" with "the hub denied you".
 */

/** Why a user-verification prompt is being shown (UIUX binds copy to this — the reason, not the UI). */
enum class UvReason { OPERATOR_AUTH }

/** A local UV failure kind — retryable; feeds honest, distinct UI (never "hub rejected"). */
enum class UvFailReason { WRONG_PIN, CANCELLED, LOCKED_OUT }

/** The result of the explicit app-level user-verification (NOT silent — user presence is the anti-seizure point). */
sealed interface UvOutcome {
    data object Verified : UvOutcome
    data class Denied(val reason: UvFailReason) : UvOutcome
    /** No UV mechanism available on this platform/config (e.g. no authenticator) — fail-closed, not a bypass. */
    data object Unavailable : UvOutcome
}

/**
 * The explicit app-level user-verification seam (the PIN/passphrase prompt for the Raw path; the platform UV for
 * Fido2). The **actual** (a Compose dialog) is UIUX-designed + injected at the composition root; tests inject a
 * stub. **Never silent** — a `Verified` MUST reflect real user presence.
 */
fun interface UserVerification {
    suspend fun verify(reason: UvReason): UvOutcome
}

/** Provides a fresh, unpredictable per-PoP nonce (jvm actual = `SecureRandom`; tests inject a fixed/counting stub). */
fun interface NonceGenerator {
    fun nonce(): ByteArray
}

/**
 * The PoP-result taxonomy — the **local** signing outcome (this device), deliberately separate from the hub's
 * verdict. [UvFailed] and [NotEnrolled]/[AuthenticatorUnavailable] are all **local + non-terminal** (retry / enroll
 * / fall back), never a hub reject.
 */
sealed interface PopResult {
    data class Signed(val pop: DevicePoP) : PopResult
    /** Local user-verification failed — RETRYABLE (wrong PIN / cancelled). NOT a hub reject (UIUX honesty). */
    data class UvFailed(val reason: UvFailReason) : PopResult
    /** No operator device-key enrolled on this device → enrollment flow, not an auth failure. */
    data object NotEnrolled : PopResult
    /** The authenticator is unavailable here (e.g. Fido2 on Linux) → the caller falls back to the Raw path. */
    data object AuthenticatorUnavailable : PopResult
}

/**
 * Holds the operator device-key **natively** (the CP never holds it) and signs a channel-bound challenge behind a
 * real UV. Two impls: `KeystoreOperatorDeviceKeyStore` (Raw software key + app-PIN, the Linux/CI base) and
 * `Fido2OperatorDeviceKeyStore` (platform authenticator, macOS/Win later). Modeled as an interface (not
 * `expect`/`actual`) so the OS-selection happens at the composition root and tests inject a fake.
 */
interface OperatorDeviceKeyStore {
    fun isEnrolled(): Boolean
    /** The enrolled device-key public key (for enrollment/registration + hub pinning), or `null` if not enrolled. */
    fun devicePublicKey(): ByteArray?
    /** Sign [challenge] behind a mandatory UV. Fail-closed: UV denied/unavailable ⇒ NO signature ([PopResult.UvFailed]/[AuthenticatorUnavailable]). */
    suspend fun sign(challenge: ByteArray): PopResult
}

/** The built PoP + the nonce that must travel with it (so the hub recomputes the same challenge). */
sealed interface PopBuildOutcome {
    data class Ready(val pop: DevicePoP, val nonce: ByteArray) : PopBuildOutcome
    data class UvFailed(val reason: UvFailReason) : PopBuildOutcome
    data object NotEnrolled : PopBuildOutcome
    data object AuthenticatorUnavailable : PopBuildOutcome
}

/**
 * Transport-independent PoP builder: fresh nonce → [operatorAuthChallenge] over the **live** `handshakeHash` →
 * `store.sign` (which enforces UV). The transport-dependent `ClientOperatorAuth` (implements
 * `net.hub.remote.OperatorAuthenticator`) will call this, send `{cpJwt, pop, nonce}` over the tunnel, and read the
 * hub grant — that wiring waits on RR5.
 */
class OperatorPopBuilder(
    private val store: OperatorDeviceKeyStore,
    private val nonceGenerator: NonceGenerator,
) {
    /** CYP-525: is an operator device-key enrolled on THIS device? Checked first (before any CP round-trip / UV
     *  prompt) so "not enrolled" routes to the enroll step, not a reject. */
    fun isEnrolled(): Boolean = store.isEnrolled()

    /** CYP-525: the enrolled device public key in the **ratified wire form (raw-32B)** — carried in the tunnel-auth
     *  request so the hub can TOFU first-enroll it; `null` when not enrolled. Converted through the `:core`
     *  single-source [ed25519PublicKeyToRaw] (the store exposes X.509 SPKI) — the one encoding boundary, no drift. */
    fun devicePublicKeyRaw(): ByteArray? = store.devicePublicKey()?.let { ed25519PublicKeyToRaw(it) }

    suspend fun buildPop(handshakeHash: ByteArray, hubId: String): PopBuildOutcome {
        val nonce = nonceGenerator.nonce()
        val challenge = operatorAuthChallenge(handshakeHash, hubId, nonce)
        return when (val r = store.sign(challenge)) {
            is PopResult.Signed -> PopBuildOutcome.Ready(r.pop, nonce)
            is PopResult.UvFailed -> PopBuildOutcome.UvFailed(r.reason)
            PopResult.NotEnrolled -> PopBuildOutcome.NotEnrolled
            PopResult.AuthenticatorUnavailable -> PopBuildOutcome.AuthenticatorUnavailable
        }
    }
}
