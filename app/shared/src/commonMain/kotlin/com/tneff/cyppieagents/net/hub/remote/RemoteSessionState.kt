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
    /**
     * CYP-478 — the operator REJECTED the first-use OOB fingerprint (poisoned-first-pin defence). Terminal,
     * fail-closed: nothing was pinned, no silent retry, re-pin is OOB-only. Distinct from [TrustChanged] (a key
     * that *changed* after a prior pin) — this is a *first-use* decline. The reject arrives from the trust layer as
     * a `TrustConfirmationRejectedException` (a `CancellationException`); the session converts it to THIS explicit
     * terminal state rather than letting it unwind the connect loop as a raw cancellation (stale mid-connect limbo).
     */
    data object TrustRejected : RemoteFailure
    /** The hub's OperatorAssertionVerifier said no (a∧b∧c failed) — fail-closed. */
    data object AuthRejected : RemoteFailure
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
    suspend fun authenticate(tunnel: NoiseTunnel, hubId: String): Boolean
}
