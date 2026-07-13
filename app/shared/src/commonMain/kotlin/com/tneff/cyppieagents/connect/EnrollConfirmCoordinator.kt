package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.operator.EnrollConfirmer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CYP-525 §2 — the live [EnrollConfirmer] the hubConnect flow observes to surface the first-enroll
 * `RecoveryCodesReveal` **during** RR3 auth (the reveal happens before the finalize grant, not at CONNECTED). It
 * bridges `ClientOperatorAuth` (which calls [confirmSavedCodes] and suspends) ↔ the ViewModel (which surfaces
 * [state] as `RevealCodes` and calls [confirmSaved]/[abort]). Mirrors the [OobConfirmCoordinator] pattern.
 *
 * `null` in the ViewModel ⇒ INERT (no live enroll; the default fail-closed no-op confirmer aborts first-enroll).
 */
sealed interface EnrollConfirmState {
    data object Idle : EnrollConfirmState
    /** The hub's minted first-enroll codes are shown; awaiting the operator's "saved" confirmation. */
    data class Revealing(val codes: List<String>) : EnrollConfirmState
}

interface EnrollConfirmCoordinator : EnrollConfirmer {
    val state: StateFlow<EnrollConfirmState>
    /** The operator confirmed they saved the codes → resolve [confirmSavedCodes] with `true` (⇒ `SavedAck`). */
    fun confirmSaved()
    /** Abort (leave / teardown) → resolve [confirmSavedCodes] with `false` (fail-closed, NO `SavedAck`). */
    fun abort()
}

/**
 * The production [EnrollConfirmCoordinator]: [confirmSavedCodes] publishes [EnrollConfirmState.Revealing] and
 * suspends on a fresh waiter until [confirmSaved]/[abort]; the state returns to [EnrollConfirmState.Idle] in a
 * `finally` (so a cancellation during the reveal also unwinds cleanly, fail-closed). One enroll in flight at a time
 * (Q5 exactly-one-hub): a new confirm resolves any stale waiter `false` first.
 */
class LiveEnrollConfirmCoordinator : EnrollConfirmCoordinator {
    private val _state = MutableStateFlow<EnrollConfirmState>(EnrollConfirmState.Idle)
    override val state: StateFlow<EnrollConfirmState> = _state.asStateFlow()
    private var waiter: CompletableDeferred<Boolean>? = null

    override suspend fun confirmSavedCodes(codes: List<String>): Boolean {
        waiter?.complete(false) // resolve any stale waiter fail-closed before a new reveal (exactly-one)
        val deferred = CompletableDeferred<Boolean>()
        waiter = deferred
        _state.value = EnrollConfirmState.Revealing(codes)
        return try {
            deferred.await()
        } finally {
            _state.value = EnrollConfirmState.Idle
            if (waiter === deferred) waiter = null
        }
    }

    override fun confirmSaved() { waiter?.complete(true) }
    override fun abort() { waiter?.complete(false) }
}
