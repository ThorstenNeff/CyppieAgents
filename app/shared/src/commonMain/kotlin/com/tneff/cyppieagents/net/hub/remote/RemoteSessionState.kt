package com.tneff.cyppieagents.net.hub.remote

import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.noise.RelayChannel

/**
 * CYP-443 Slice 1 — the remote connection/session state model (CYP-440 §3.6, CYP-429 §7 + Q3/Q5, H1–H8). The
 * [RemoteHubSession] drives these states over the Noise tunnel; the seams below ([HubTrust] = Slice 3 pin,
 * [OperatorAuthenticator] = Slice 2 PoP) plug in later, so the state machine is complete now and layered.
 */

/** The ConnectingView idiom (CYP-429 §7): relayDialing → e2eHandshake → trustCheck → authenticating → connected. */
enum class RemoteConnState {
    RELAY_DIALING, E2E_HANDSHAKE, TRUST_CHECK, AUTHENTICATING, CONNECTED, RECONNECTING, LOST,
}

/** Typed failures (CYP-429 `remote.error.<cause>`). [TrustChanged] + [AuthRejected] are **terminal** (no silent retry). */
sealed interface RemoteFailure {
    data object RelayUnreachable : RemoteFailure
    data object HubOffline : RemoteFailure
    data object HandshakeFailed : RemoteFailure
    /** Hub key ≠ pin — a hard block, NOT a mere error (H1/H2, CI-5); needs OOB re-pin. */
    data class TrustChanged(val expectedFingerprint: String) : RemoteFailure
    /** The hub's OperatorAssertionVerifier said no (a∧b∧c failed) — fail-closed. */
    data object AuthRejected : RemoteFailure
    /**
     * CYP-525 Finding ① — a first-enroll code set did not arrive intact (empty/truncated/blank `EnrollResponse`).
     * **Retryable** (a delivery problem, reconnect), NOT the terminal [AuthRejected] mis-attribution ("re-login").
     * Fail-closed upstream (no `SavedAck`, no CONNECTED); this is purely the honest attribution + retry affordance.
     */
    data object EnrollCodesUnavailable : RemoteFailure
    /**
     * CYP-525 — **this device has no enrolled operator key** (a distinct third truth, never collapsed into
     * [AuthRejected]): the hub didn't reject us, we simply haven't set this device up yet. Actionable → the enroll
     * step ("set up this device"), NOT a dead reject. Distinct so the UI routes to enroll instead of "denied".
     */
    data object DeviceNotEnrolled : RemoteFailure

    /**
     * CYP-525 F3 (Reviewer) — a **local user-verification** failure (wrong app-PIN / cancelled) during the operator
     * PoP. **Retryable**, NOT terminal — and NEVER [AuthRejected] ("the hub denied you"): the hub never saw a
     * request. The UI offers a retry (re-enter the PIN); distinct from the hub's terminal reject.
     */
    data object OperatorUvFailed : RemoteFailure
}

/**
 * Q3 latency **advisory** (H7): a damped, neutral hint — never an alarm, never red/amber. High latency ≠
 * disconnected. `null` = unknown (honest absence, never a fabricated 0).
 */
data class LatencyHint(val roundTripMillis: Long, val degraded: Boolean)

/**
 * The whole remote session state. [inFlightUncertain] (H4): on a relay-drop, in-flight work is honestly marked
 * uncertain — never silently assumed done; the VMs (H5) resolve optimistic-vs-confirmed from it.
 */
data class RemoteSessionState(
    val hubId: String,
    val conn: RemoteConnState,
    val failure: RemoteFailure? = null,
    val latency: LatencyHint? = null,
    val inFlightUncertain: Boolean = false,
)

/** Opens the relay pipe to a hub (Phase-2 = CP rendezvous + relay WS, RR4/RR5). Throws ⇒ relay unreachable. */
fun interface RelayDialer {
    suspend fun dial(hubId: String): RelayChannel
}

/**
 * CYP-494 — a dial failure that carries the [RemoteFailure] the session should surface. A plain dial exception
 * maps to the generic [RemoteFailure.RelayUnreachable]; a dialer that knows the typed cause (e.g. the CP said the
 * hub is NOT_REGISTERED vs the relay is RELAY_UNAVAILABLE) throws this so the operator sees the RIGHT reason.
 * Not a [kotlin.coroutines.cancellation.CancellationException] → it flows through the session's transient path.
 */
class RelayDialException(val failure: RemoteFailure) : Exception("relay dial failed: $failure")

/** Resolves which hub static to handshake against — the TOFU decision (Slice 3 `HubKeyPin`, CI-1/CI-5). */
fun interface HubTrust {
    suspend fun resolve(hubId: String): TrustResolution
}

sealed interface TrustResolution {
    /** An existing pin — handshake against THIS key (never the CP-registry key, CI-1). */
    data class Pinned(val hubStatic: ByteArray) : TrustResolution
    /** First use — TOFU-adopt after an OOB fingerprint confirm (the confirm/UX is the Slice-3 impl's concern). */
    data class FirstUse(val hubStatic: ByteArray, val fingerprint: String) : TrustResolution
    /** The registry static differs from the pin ⇒ HARD BLOCK (CI-5), terminal; needs OOB re-pin. */
    data class Changed(val expectedFingerprint: String) : TrustResolution
}

/**
 * Proves operator authority over the **live** tunnel's `h` (Slice 2 WebAuthn-PoP: `challenge ==
 * H(h ‖ hubId ‖ nonce ‖ "operator-auth")`, UV required). Returns true iff the hub granted (a∧b∧c). Fail-closed:
 * any error ⇒ not granted.
 */
fun interface OperatorAuthenticator {
    suspend fun authenticate(tunnel: NoiseTunnel, hubId: String): OperatorAuthOutcome
}

/**
 * CYP-525 — the RR3 tunnel-auth outcome as **three distinct truths** (never two-valued): the hub granted us
 * ([Granted]), the hub rejected us ([Rejected] → terminal [RemoteFailure.AuthRejected]), or **this device isn't
 * enrolled yet** ([DeviceNotEnrolled] → the enroll step, [RemoteFailure.DeviceNotEnrolled]) — the last must NEVER
 * collapse into a reject (that is the bug this fixes: "not set up" read as "denied"). Fail-closed: any local PoP
 * failure or thrown error that is not specifically "not enrolled" is a [Rejected], never a false grant.
 */
sealed interface OperatorAuthOutcome {
    data object Granted : OperatorAuthOutcome
    data object Rejected : OperatorAuthOutcome
    data object DeviceNotEnrolled : OperatorAuthOutcome

    /**
     * CYP-525 F3 — a **local** user-verification failure (wrong app-PIN / cancelled) during the PoP build. Retryable,
     * NEVER a hub reject (no request was sent). Distinct from [Rejected] so the session surfaces
     * [RemoteFailure.OperatorUvFailed] (retry), never terminal [RemoteFailure.AuthRejected].
     */
    data object UvFailed : OperatorAuthOutcome

    /**
     * CYP-525 Finding ① (UIUX honesty) — a first-enroll [com.tneff.cyppieagents.operator.EnrollResponse] arrived but
     * failed H3 (empty / truncated / over-count / blank) ⇒ the codes did NOT arrive intact. That is a **delivery**
     * problem, NOT a hub rejection: retryable-reconnect, never the terminal [Rejected]/[RemoteFailure.AuthRejected]
     * ("re-login") mis-attribution. Fail-closed stays intact (no `SavedAck`, no CONNECTED) — this only fixes the
     * attribution so the session surfaces the retryable [RemoteFailure.EnrollCodesUnavailable].
     */
    data object EnrollCodesUnavailable : OperatorAuthOutcome
}
