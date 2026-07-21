package com.tneff.cyppieagents.net.hub.remote

import com.tneff.cyppieagents.net.Backoff
import com.tneff.cyppieagents.net.hub.noise.ClientNoiseTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.trust.TrustConfirmationRejectedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * CYP-443 Slice 1 — the remote session orchestrator (CYP-440 §3.6). Drives the connect sequence over the Noise
 * tunnel and owns reconnect / Q5 teardown / Q3 latency / H4 in-flight honesty. The crypto-auth steps are injected
 * seams ([HubTrust] = Slice 3 pin, [OperatorAuthenticator] = Slice 2 PoP), so this state machine is complete now
 * and Slices 2/3 slot in without touching it.
 *
 * **Exactly one active hub (CYP-429 Q5):** the hub picker holds ONE session; a switch is [close] (full teardown)
 * of the old + a fresh session for the new — nothing is carried across (CI-6). All fail-closed.
 */
class RemoteHubSession(
    val hubId: String,
    private val transport: ClientNoiseTransport,
    private val dialer: RelayDialer,
    private val trust: HubTrust,
    private val authenticator: OperatorAuthenticator,
    private val scope: CoroutineScope,
    private val backoff: Backoff = Backoff(),
    private val prologue: ByteArray = ByteArray(0),
    private val latencyDamping: Double = 0.3,
    private val latencyDegradedMillis: Long = 400L,
    /**
     * CYP-783 — a TEST-ONLY suspension seam invoked at a FIXED source point immediately BEFORE the CONNECTED
     * announce (`_state.update{CONNECTED}`). Prod default = no-op (zero behaviour, zero cost). Its sole purpose is
     * to make the announce/arm-latch ORDERING deterministically observable: the arm-then-announce fix means the
     * drop latch is already armed when this hook runs, so a `reportDropped()` from inside the hook is CAPTURED
     * (→ reconnect). Regress the order (arm AFTER announce) and the same hook sees an UNARMED latch → the drop is
     * lost → the session wedges CONNECTED forever. The regression tooth drives exactly that (deterministic,
     * non-vacuous), so the ordering can never silently drift back. Never overridden in prod.
     */
    private val onBeforeAnnounceConnected: suspend () -> Unit = {},
) {
    private val _state = MutableStateFlow(RemoteSessionState(hubId, RemoteConnState.RELAY_DIALING))
    val state: StateFlow<RemoteSessionState> = _state.asStateFlow()

    /** The live tunnel once CONNECTED — the CR3 Ktor engine will run HTTP/WS over it; `null` otherwise. */
    var tunnel: NoiseTunnel? = null
        private set

    private var job: Job? = null
    private var closed = false
    private var drop: CompletableDeferred<Unit>? = null
    private var latencyEma: Double? = null

    fun start() {
        if (job != null) return
        job = scope.launch { runLoop() }
    }

    /** The transport consumer detected the tunnel died (EOF/error) → triggers reconnect. */
    fun reportDropped() { drop?.complete(Unit) }

    /** Q5 full teardown (exactly-one-hub): unwind the loop, close the tunnel, go LOST. Idempotent. */
    suspend fun close() {
        closed = true
        drop?.complete(Unit)
        job?.cancel()
        tunnel?.let { runCatching { it.close() } }
        tunnel = null
        _state.update { it.copy(conn = RemoteConnState.LOST, inFlightUncertain = false) }
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (!closed) {
            when (attemptConnect()) {
                Outcome.DROPPED -> {
                    if (closed) break
                    _state.update { it.copy(conn = RemoteConnState.RECONNECTING, inFlightUncertain = true) } // H4
                    attempt = 1 // was connected → start a fresh (small) backoff, escalating only on repeated failure
                }
                Outcome.TRANSIENT -> {
                    if (closed) break
                    _state.update { it.copy(conn = RemoteConnState.RECONNECTING) }
                    attempt++
                }
                Outcome.TERMINAL -> return // TrustChanged / AuthRejected — state already LOST; no silent retry (CI-5)
                Outcome.CLOSED -> return
            }
            delay(backoff.delayFor(attempt))
        }
    }

    private suspend fun attemptConnect(): Outcome {
        _state.update { it.copy(conn = RemoteConnState.RELAY_DIALING, failure = null) }
        val relay = try {
            dialer.dial(hubId)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            // CYP-494: a typed dial failure (RelayDialException) surfaces its own cause (e.g. HubOffline vs
            // RelayUnreachable); any other dial error falls back to the generic RelayUnreachable. Still transient.
            _state.update { it.copy(failure = (e as? RelayDialException)?.failure ?: RemoteFailure.RelayUnreachable) }
            return Outcome.TRANSIENT
        }

        val resolution = try {
            trust.resolve(hubId)
        } catch (r: TrustConfirmationRejectedException) {
            // CYP-478/696: an operator OOB-fingerprint REJECT (poisoned-first-pin defence). It arrives as a
            // CancellationException SUBCLASS, but it is a DELIBERATE terminal verdict — not a coroutine teardown.
            // Convert it here to an explicit fail-closed terminal LOST; do NOT let it fall through to the generic
            // `catch (CancellationException) { throw c }` and unwind the loop as a raw cancellation, which would
            // leave the session stuck in a stale mid-connect state (conn=RELAY_DIALING, failure=null). Nothing was
            // pinned; re-pin is OOB-only, so there is no silent retry / re-prompt loop.
            runCatching { relay.close() }
            _state.update { it.copy(conn = RemoteConnState.LOST, failure = RemoteFailure.TrustRejected) }
            return Outcome.TERMINAL
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            runCatching { relay.close() }
            _state.update { it.copy(failure = RemoteFailure.RelayUnreachable) }
            return Outcome.TRANSIENT
        }
        val hubStatic = when (resolution) {
            is TrustResolution.Pinned -> resolution.hubStatic
            is TrustResolution.FirstUse -> resolution.hubStatic
            is TrustResolution.Changed -> {
                runCatching { relay.close() }
                _state.update {
                    it.copy(conn = RemoteConnState.LOST, failure = RemoteFailure.TrustChanged(resolution.expectedFingerprint))
                }
                return Outcome.TERMINAL // CI-5 hard block — never a silent re-pin
            }
        }

        _state.update { it.copy(conn = RemoteConnState.E2E_HANDSHAKE) }
        val t = try {
            transport.connect(hubStatic, relay, prologue)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            runCatching { relay.close() }
            _state.update { it.copy(failure = RemoteFailure.HandshakeFailed) }
            return Outcome.TRANSIENT
        }

        // Handshake succeeded AGAINST the pin ⇒ hub authenticity holds (trustCheck passes; CI-1).
        _state.update { it.copy(conn = RemoteConnState.TRUST_CHECK) }
        _state.update { it.copy(conn = RemoteConnState.AUTHENTICATING) }
        val outcome = try {
            authenticator.authenticate(t, hubId)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            OperatorAuthOutcome.Rejected // any thrown error ⇒ fail-closed reject, never a false grant / false enroll
        }
        when (outcome) {
            OperatorAuthOutcome.Granted -> Unit // proceed to CONNECTED below
            OperatorAuthOutcome.DeviceNotEnrolled -> {
                // CYP-525: NOT a reject — this device simply isn't set up. Distinct terminal failure so the UI
                // routes to the enroll step ("set up this device"), never "denied" (the bug this fixes).
                runCatching { t.close() }
                _state.update { it.copy(conn = RemoteConnState.LOST, failure = RemoteFailure.DeviceNotEnrolled) }
                return Outcome.TERMINAL
            }
            OperatorAuthOutcome.Rejected -> {
                runCatching { t.close() }
                _state.update { it.copy(conn = RemoteConnState.LOST, failure = RemoteFailure.AuthRejected) }
                return Outcome.TERMINAL // fail-closed (a∧b∧c said no)
            }
            OperatorAuthOutcome.UvFailed -> {
                // CYP-525 F3: a local UV failure (wrong PIN / cancelled) — retryable, NEVER "hub rejected" (nothing
                // was sent). Terminal for THIS attempt (tunnel torn down), but the UI offers a retry (re-enter PIN).
                runCatching { t.close() }
                _state.update { it.copy(conn = RemoteConnState.LOST, failure = RemoteFailure.OperatorUvFailed) }
                return Outcome.TERMINAL
            }
            OperatorAuthOutcome.EnrollCodesUnavailable -> {
                // CYP-525 Finding ①: the first-enroll codes didn't arrive intact (H3-invalid) — a DELIVERY problem,
                // NOT a hub reject. Distinct retryable failure so the UI reads "codes didn't arrive — reconnect"
                // (mirrors DeviceNotEnrolled: retry affordance, not the terminal-relogin AuthRejected). Fail-closed
                // holds: nothing was pinned/finalized, no SavedAck was sent, never CONNECTED.
                runCatching { t.close() }
                _state.update { it.copy(conn = RemoteConnState.LOST, failure = RemoteFailure.EnrollCodesUnavailable) }
                return Outcome.TERMINAL
            }
            OperatorAuthOutcome.EnrollTimedOut -> {
                // CYP-595: an RR3 network receive (esp. the post-SavedAck final grant) exceeded its bound — the hub
                // stalled. Retryable "window expired — reconnect", NOT the terminal AuthRejected and NOT EnrollCodesUnavailable
                // (the codes DID arrive) — a distinct truth so the operator reconnects for fresh codes instead of hanging.
                runCatching { t.close() }
                _state.update { it.copy(conn = RemoteConnState.LOST, failure = RemoteFailure.EnrollTimedOut) }
                return Outcome.TERMINAL
            }
        }

        tunnel = t
        // CYP-774: ARM the drop latch BEFORE announcing CONNECTED. A consumer (or a test) that observes
        // CONNECTED and immediately reports a drop must never lose the signal in the announce->arm window — that
        // lost drop leaves d.await() blocked forever (session stuck CONNECTED, no reconnect). Arm-then-announce.
        val d = CompletableDeferred<Unit>()
        drop = d
        onBeforeAnnounceConnected() // CYP-783: test seam at the announce/arm boundary (prod no-op) — the arm above must precede the announce below
        _state.update { it.copy(conn = RemoteConnState.CONNECTED, failure = null, inFlightUncertain = false) }
        // Stay connected until a drop is reported or teardown unwinds us.
        try {
            d.await()
        } finally {
            drop = null
            tunnel = null
            runCatching { t.close() }
        }
        return if (closed) Outcome.CLOSED else Outcome.DROPPED
    }

    /** Q3 latency (H7): damped EMA + a neutral degraded flag; NEVER changes [RemoteConnState] (high latency ≠ down). */
    fun recordLatency(roundTripMillis: Long) {
        val prev = latencyEma
        val ema = if (prev == null) roundTripMillis.toDouble() else prev + latencyDamping * (roundTripMillis - prev)
        latencyEma = ema
        _state.update { it.copy(latency = LatencyHint(ema.toLong(), degraded = ema >= latencyDegradedMillis)) }
    }

    private enum class Outcome { DROPPED, TRANSIENT, TERMINAL, CLOSED }
}
