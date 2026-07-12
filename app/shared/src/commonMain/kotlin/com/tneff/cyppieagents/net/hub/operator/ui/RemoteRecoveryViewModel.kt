package com.tneff.cyppieagents.net.hub.operator.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * CYP-479 — the Q6 recovery-input flow/state (CYP-480 §3.2): the operator enters a backup/recovery code to
 * re-enroll (= re-pin) a lost device. Drives [RecoveryInputContent]. The actual verify is a seam
 * ([RecoveryCodeVerifier]) whose real backing is server-owned (CYP-459/469) and not built yet — this VM builds
 * the flow against a stub, honestly.
 *
 * Fail-closed axes ([RecoveryError]): a wrong/used code is **retryable** (`InvalidCode`, error tone, field
 * stays usable); no-codes-left is **terminal** (`Exhausted`, neutral OOB-at-hub redirect). A verifier failure
 * is treated as a retryable invalid (never a silent success). Central-login-alone is not a path (HE).
 */
class RemoteRecoveryViewModel(
    private val verifier: RecoveryCodeVerifier,
    private val onRecovered: () -> Unit = {},
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope

    data class State(
        val code: String = "",
        val submitting: Boolean = false,
        val error: RecoveryError? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Editing clears a stale error — but NOT the terminal [RecoveryError.Exhausted] (no more codes to try). */
    fun onCodeChange(code: String) {
        _state.update { s ->
            val keepTerminal = s.error?.isTerminal == true
            s.copy(code = code, error = if (keepTerminal) s.error else null)
        }
    }

    fun submit() {
        val current = _state.value
        // Fail-closed guards: no blank submit, no double-submit, no retry past the terminal exhausted state.
        if (current.code.isBlank() || current.submitting || current.error?.isTerminal == true) return
        _state.update { it.copy(submitting = true, error = null) }
        runScope.launch {
            val result = runCatching { verifier.verify(current.code) }
                .getOrElse { RecoveryVerifyResult.Invalid } // a verifier failure is never a silent success
            when (result) {
                RecoveryVerifyResult.Accepted -> {
                    _state.update { it.copy(submitting = false, error = null) }
                    onRecovered()
                }
                RecoveryVerifyResult.Invalid ->
                    _state.update { it.copy(submitting = false, error = RecoveryError.InvalidCode) }
                RecoveryVerifyResult.Exhausted ->
                    _state.update { it.copy(submitting = false, error = RecoveryError.Exhausted) }
            }
        }
    }
}
