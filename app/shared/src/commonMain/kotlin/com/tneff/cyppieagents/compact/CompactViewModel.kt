package com.tneff.cyppieagents.compact

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tneff.cyppieagents.model.CompactConfig
import com.tneff.cyppieagents.model.CompactStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * CYP-326 — UI state for the compact-orchestration window. The window is a **server-owned mirror** (§3-3):
 * everything shown comes from the loaded [CompactStatus]; the UI never fabricates "allowed"/"running"/"idle".
 *
 * **Honesty (unknown ≠ idle/off):** [status] is `null` until the server state resolves and stays `null` on a
 * failed load — the panel then renders the status/threshold rows **absent**, never a defaulted "idle"/"off"
 * (the CYP-312 never-optimistic · CYP-315 failed≠remote · CYP-316 null≠0 line). Only [editable] (the operator
 * flag) is a local fact.
 */
data class CompactUiState(
    val loading: Boolean = true,
    /** Operator token present → the "compact allowed" control is a live checkbox; else a read-only chip. */
    val editable: Boolean = false,
    /** Server-owned status; `null` = UNKNOWN (unresolved / load failed) → honest absence, never a default. */
    val status: CompactStatus? = null,
    /** CYP-327 Feature A: the server-CONFIRMED threshold just set (tokens), for a transient INFO confirmation.
     *  `null` = no confirmation showing. Set only after `onSuccess` (never optimistic); self-clears. */
    val thresholdSetConfirm: Int? = null,
)

/** CYP-327: how long the transient threshold-set confirmation stays visible before it self-clears. */
private const val CONFIRM_VISIBLE_MS = 3_000L

/**
 * Drives the compact-orchestration window over a [CompactRepository] (stub today; the live
 * [CompactHttpRepository] after Backend's Milestone-C endpoints — no VM/UI change). **Global, not
 * project-scoped:** the gate is team-wide, so one instance backs the window (NOT re-keyed per project).
 *
 * The "compact allowed" toggle is **operator-gated + never optimistic**: [setAllowed] no-ops without
 * [CompactUiState.editable] (defence in depth — the server also 403s) and writes through the repository;
 * the checkbox reflects the **server's** returned [CompactStatus.allowed], so it moves only once the server
 * confirms — never a client guess.
 */
class CompactViewModel(
    private val repository: CompactRepository,
    editable: Boolean = false,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow(CompactUiState(editable = editable))
    val state: StateFlow<CompactUiState> = _state.asStateFlow()

    init { runScope.launch { load() } }

    private suspend fun load() {
        // Fail-closed: a failed load leaves status == null (UNKNOWN) → the panel renders the server-mirror rows
        // absent, never a fabricated "idle"/"off" (§3-3). Only loading flips.
        val status = runCatching { repository.getStatus() }.getOrNull()
        _state.update { it.copy(status = status, loading = false) }
    }

    /**
     * Operator-only server-mirror toggle. Fail-closed (no-op without [CompactUiState.editable]); never
     * optimistic — on success the state adopts the server's [CompactStatus]; on failure the mirror is left
     * UNCHANGED (a rejected toggle simply doesn't move the checkbox — the server stays the source of truth).
     */
    fun setAllowed(allowed: Boolean) {
        if (!_state.value.editable) return
        val threshold = _state.value.status?.thresholdTokens ?: CompactConfig().thresholdTokens
        runScope.launch {
            runCatching { repository.setConfig(CompactConfig(allowed = allowed, thresholdTokens = threshold)) }
                .onSuccess { s -> _state.update { it.copy(status = s) } }
            // onFailure: keep the server-mirror unchanged — no optimistic flip.
        }
    }

    /**
     * CYP-327 Feature A — operator-only server-mirror threshold write, same discipline as [setAllowed]:
     * fail-closed (no-op without [CompactUiState.editable]) and NEVER optimistic — the displayed threshold
     * adopts the server's returned [CompactStatus.thresholdTokens]; on failure the mirror is left unchanged.
     * Only the threshold changes; the current [allowed] gate is preserved.
     */
    fun setThreshold(thresholdTokens: Int) {
        if (!_state.value.editable) return
        val allowed = _state.value.status?.allowed ?: false
        runScope.launch {
            runCatching { repository.setConfig(CompactConfig(allowed = allowed, thresholdTokens = thresholdTokens)) }
                .onSuccess { s ->
                    // Adopt the server-confirmed status AND raise the transient INFO confirmation on the
                    // server-confirmed value (never the drafted one) — post-server, never optimistic.
                    _state.update { it.copy(status = s, thresholdSetConfirm = s.thresholdTokens) }
                    confirmJob?.cancel()
                    confirmJob = runScope.launch {
                        delay(CONFIRM_VISIBLE_MS)
                        _state.update { it.copy(thresholdSetConfirm = null) }
                    }
                }
            // onFailure: keep the server-mirror unchanged — no optimistic change, no confirmation.
        }
    }

    // CYP-327: the self-clear watchdog for the transient threshold confirmation (cancelled if a newer set arrives).
    private var confirmJob: Job? = null
}
