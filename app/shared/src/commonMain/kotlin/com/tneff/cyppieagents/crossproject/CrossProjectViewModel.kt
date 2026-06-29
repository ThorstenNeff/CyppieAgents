package com.tneff.cyppieagents.crossproject

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Immutable UI state for one channel's cross-project authorization (CYP-93). The authorize action is gated
 * behind a deliberate **owner consent** ([ownerConsent]) — never a silent default. Fail-closed: every
 * mutation is a no-op without [editable] (the server also enforces the operator/owner gate, §2.6).
 */
data class CrossProjectUiState(
    val channelId: String,
    /** Operator/owner token present → authorize/revoke enabled; else read-only + gate hint (fail-closed). */
    val editable: Boolean = false,
    val status: CrossShareStatus? = null,
    val dialogOpen: Boolean = false,
    /** The owner-consent affordance in the dialog (a deliberate step, not a default). */
    val ownerConsent: Boolean = false,
    val error: String? = null,
) {
    val shared: Boolean get() = status?.shared == true
    val reachableMembers: List<CrossMember> get() = status?.reachableMembers ?: emptyList()
    /** The channel has members in other projects → it can/does span the boundary (badge-worthy). */
    val isCrossProject: Boolean get() = reachableMembers.isNotEmpty()
    val sharedAt: Long? get() = status?.sharedAt
    /** Confirm needs the explicit owner consent (and the gate) — visible before the action. */
    val canConfirm: Boolean get() = editable && ownerConsent
}

/**
 * Drives one channel's cross-project authorization (CYP-93) over a [CrossProjectRepository] (stub today; a
 * later stub→real swap, no UI change). Authorization is a **directed owner act** — there is NO affordance
 * to accept a share from a message/agent request (anti-injection §2.6, load-bearing). After authorize/revoke
 * the status is re-fetched so the badge/status reflect the server truth; revoke is immediately fail-closed.
 */
class CrossProjectViewModel(
    private val repository: CrossProjectRepository,
    val channelId: String,
    editable: Boolean = false,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow(CrossProjectUiState(channelId = channelId, editable = editable))
    val state: StateFlow<CrossProjectUiState> = _state.asStateFlow()

    init { runScope.launch { reload() } }

    private suspend fun reload() {
        runCatching { repository.status(channelId) }
            .onSuccess { s -> _state.update { it.copy(status = s) } }
            .onFailure { e -> if (e is CancellationException) throw e } // keep prior; fail-closed (no invented state)
    }

    fun openDialog() {
        if (!_state.value.editable) return
        _state.update { it.copy(dialogOpen = true, ownerConsent = false, error = null) }
    }

    fun closeDialog() = _state.update { it.copy(dialogOpen = false, error = null) }
    fun setOwnerConsent(consent: Boolean) = _state.update { it.copy(ownerConsent = consent, error = null) }

    fun confirmAuthorize() {
        val s = _state.value
        if (!s.canConfirm) return // fail-closed + owner-consent guard (server also enforces)
        runScope.launch {
            runCatching { repository.authorize(channelId) }
                .onSuccess { st -> _state.update { it.copy(status = st, dialogOpen = false, ownerConsent = false, error = null) } }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(error = errorKey(e)) }
                }
        }
    }

    fun revoke() {
        if (!_state.value.editable) return
        runScope.launch {
            runCatching { repository.revoke(channelId) }
                .onSuccess { st -> _state.update { it.copy(status = st, error = null) } }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(error = errorKey(e)) }
                }
        }
    }

    private fun errorKey(e: Throwable): String = when ((e as? CrossProjectException)?.code) {
        "operator_required", "unauthorized" -> "crossproject_operator_required"
        else -> "crossproject_error"
    }
}
