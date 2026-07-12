package com.tneff.cyppieagents.net.hub.trust

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CYP-478 — the **out-of-band (OOB) fingerprint confirmation** gate that guards first-use TOFU adoption
 * (poisoned-first-pin defence). [TofuHubTrust] calls [awaitConfirmation] before it ever pins a hub's key on
 * first contact; the operator must compare the presented [HubKeyFingerprint] against what the hub admin
 * published OOB and explicitly approve. **Adoption happens ONLY after this returns normally** — a reject or an
 * aborted flow never pins.
 *
 * A reject propagates as a [TrustConfirmationRejectedException] (a [CancellationException] subclass) so it
 * unwinds the remote-session connect coroutine **fail-closed, without a silent retry loop** — the seam's
 * generic exception path would otherwise re-drive the connect and re-prompt endlessly. The rejected/awaiting
 * surface for the UI is exposed separately via [PendingOobConfirmations.state], not through `RemoteSessionState`
 * (this keeps the merged Slice-1 session machine untouched).
 */
fun interface OobFingerprintConfirmer {
    /**
     * Suspends until the operator confirms the hub's [fingerprint] OOB. Returns normally on approval; throws
     * [TrustConfirmationRejectedException] on an explicit reject. Cancellation of the calling coroutine
     * (teardown) also aborts without adopting.
     */
    suspend fun awaitConfirmation(hubId: String, fingerprint: String)
}

/** An explicit operator reject of a first-use fingerprint. A [CancellationException] so the connect unwinds fail-closed. */
class TrustConfirmationRejectedException(hubId: String) :
    CancellationException("OOB fingerprint rejected for hub '$hubId' — key not adopted (fail-closed)")

/** The state the UIUX confirm screen renders. The visual form is UIUX's; this is the logic/state (CYP-478 seam). */
sealed interface OobConfirmState {
    data object Idle : OobConfirmState
    /** A first-use confirmation is pending — show [fingerprint] for OOB comparison + approve/reject. */
    data class Awaiting(val hubId: String, val fingerprint: String) : OobConfirmState
    /** The operator rejected the last first-use fingerprint (terminal for that attempt; fail-closed, nothing pinned). */
    data class Rejected(val hubId: String) : OobConfirmState
}

/**
 * The production [OobFingerprintConfirmer]: a state holder the UI observes ([state]) and drives ([approve] /
 * [reject]). Exactly one confirmation is outstanding at a time (the CYP-429 Q5 one-active-hub invariant). The
 * visual (fingerprint display, approve/reject affordances, OOB-source guidance) is UIUX's; this owns only the
 * request/approve/reject state machine.
 */
class PendingOobConfirmations : OobFingerprintConfirmer {
    private val _state = MutableStateFlow<OobConfirmState>(OobConfirmState.Idle)
    val state: StateFlow<OobConfirmState> = _state.asStateFlow()

    private var waiter: CompletableDeferred<Unit>? = null
    private var waitingHubId: String? = null

    override suspend fun awaitConfirmation(hubId: String, fingerprint: String) {
        val deferred = CompletableDeferred<Unit>()
        waiter = deferred
        waitingHubId = hubId
        _state.value = OobConfirmState.Awaiting(hubId, fingerprint)
        try {
            deferred.await()
        } finally {
            // Clear the waiter, but leave a terminal Rejected/Idle state in place for the UI to render.
            if (waiter === deferred) { waiter = null; waitingHubId = null }
        }
    }

    /** The operator confirmed the fingerprint OOB → the awaiting [awaitConfirmation] returns and TOFU adopts. */
    fun approve() {
        _state.value = OobConfirmState.Idle
        waiter?.complete(Unit)
    }

    /** The operator rejected the fingerprint → fail-closed: the awaiting [awaitConfirmation] throws, nothing pins. */
    fun reject() {
        val hubId = waitingHubId
        if (hubId != null) _state.value = OobConfirmState.Rejected(hubId)
        waiter?.completeExceptionally(TrustConfirmationRejectedException(hubId ?: "?"))
    }
}
