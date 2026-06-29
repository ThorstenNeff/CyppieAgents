package com.tneff.cyppieagents.connector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tneff.cyppieagents.model.ConnectorKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Immutable UI state for the connector picker + B opt-in (CYP-123, spec §3). [draftKind] is the connector the
 * operator has settled on — it only becomes [ConnectorKind.MCP] **after** a deliberate, acknowledged opt-in
 * (spec §3.2/§5.4: B is never pre-selected, A is the first-class default). The B confirm is gated behind
 * [riskAcknowledged]; fail-closed without [editable] (the server also enforces the operator gate, §3.3).
 */
data class ConnectorSelectionUiState(
    /** Operator token present → the picker/opt-in act; else fail-closed (no activation; server enforces too). */
    val editable: Boolean = false,
    /** The settled connector. STREAM_JSON (A) by default; becomes MCP (B) only on an acknowledged confirm. */
    val draftKind: ConnectorKind = ConnectorKind.STREAM_JSON,
    val optInDialogOpen: Boolean = false,
    /** The deliberate B acknowledgment (the opt-in act) — a step, never a default. */
    val riskAcknowledged: Boolean = false,
    val error: String? = null,
) {
    /** B confirm needs BOTH the operator gate and the explicit acknowledgment — visible before the action. */
    val canConfirm: Boolean get() = editable && riskAcknowledged
}

/**
 * Drives the per-agent connector selection (CYP-123, spec §3) over a [ConnectorSelectionRepository] (stub
 * until CYP-122; a later stub→real swap, no UI change). The security state machine, mirroring CYP-93:
 *
 *  - **A (stream-json)** is the plain default — [selectKind] with `STREAM_JSON` activates it immediately (no
 *    acknowledgment needed) and never opens a dialog.
 *  - **B (MCP)** is a deliberate opt-in — [selectKind] with `MCP` does NOT set the connector; it opens the
 *    opt-in dialog. The draft only becomes MCP via [confirmOptIn], and only when [ConnectorSelectionUiState.canConfirm]
 *    (operator-gated AND acknowledged). This is the **only** path to B.
 *
 * **Anti-injection invariant (spec §3.3/§5, load-bearing):** there is deliberately NO method that activates a
 * connector from message/agent/external input — the only entry points are [selectKind] (operator UI) and
 * [confirmOptIn] (operator, ack-gated). Channel/agent content is untrusted data, never an instruction here.
 */
class ConnectorSelectionViewModel(
    private val repository: ConnectorSelectionRepository,
    private val agentId: String?,
    editable: Boolean = false,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow(ConnectorSelectionUiState(editable = editable))
    val state: StateFlow<ConnectorSelectionUiState> = _state.asStateFlow()

    /**
     * The operator picked a connector kind in the picker. A is activated immediately (no ack); B opens the
     * deliberate opt-in dialog (the draft does NOT become B yet). Fail-closed without the operator gate.
     */
    fun selectKind(kind: ConnectorKind) {
        if (!_state.value.editable) return // fail-closed (server also enforces)
        when (kind) {
            ConnectorKind.STREAM_JSON -> {
                // A is the plain, first-class default — activate immediately, no dialog, no ack.
                _state.update { it.copy(draftKind = ConnectorKind.STREAM_JSON, optInDialogOpen = false, error = null) }
                activate(ConnectorSelection(ConnectorKind.STREAM_JSON, riskAcknowledged = false))
            }
            ConnectorKind.MCP -> {
                // B is a deliberate act — do NOT set the connector; open the ack-gated opt-in dialog.
                _state.update { it.copy(optInDialogOpen = true, riskAcknowledged = false, error = null) }
            }
        }
    }

    fun setRiskAcknowledged(ack: Boolean) = _state.update { it.copy(riskAcknowledged = ack, error = null) }

    /**
     * Confirm the B opt-in — the **only** path to MCP. Fail-closed: a no-op unless [ConnectorSelectionUiState.canConfirm]
     * (operator-gated AND acknowledged). On success the draft becomes MCP, the dialog closes, the ack resets;
     * on failure the error key is set and the draft stays A (no half-activation).
     */
    fun confirmOptIn() {
        val s = _state.value
        if (!s.canConfirm) return // fail-closed: operator gate + deliberate acknowledgment (server re-checks)
        runScope.launch {
            runCatching { repository.activate(agentId, ConnectorSelection(ConnectorKind.MCP, riskAcknowledged = true)) }
                .onSuccess {
                    _state.update {
                        it.copy(draftKind = ConnectorKind.MCP, optInDialogOpen = false, riskAcknowledged = false, error = null)
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(error = errorKey(e)) }
                }
        }
    }

    /** Cancel the opt-in: close the dialog, reset the ack, leave the draft unchanged (stays A). */
    fun cancelOptIn() = _state.update { it.copy(optInDialogOpen = false, riskAcknowledged = false, error = null) }

    private fun activate(selection: ConnectorSelection) {
        runScope.launch {
            runCatching { repository.activate(agentId, selection) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(error = errorKey(e)) }
                }
        }
    }

    /** Map a server/stub error to the dialog error key (the opt-in dialog surfaces `connector_optin_error`). */
    private fun errorKey(@Suppress("UNUSED_PARAMETER") e: Throwable): String = "connector_optin_error"
}
