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
    /**
     * The agent's CURRENT connector when the picker opened (CYP-126). For a NEW/A agent this is STREAM_JSON, so
     * [draftKind] starts at A and **B is never pre-selected**; for an existing **B** agent in edit it is MCP, so
     * the picker shows B selected because that's the **truth** — not a fresh opt-in pre-selection ([ConnectorSelectionViewModel.confirmOptIn]
     * remains the only path that *changes* the connector to B).
     */
    val initialKind: ConnectorKind = ConnectorKind.STREAM_JSON,
    /** Add context: the kind rides `NewAgentSpec` (the create carries it) — no connector-endpoint call, no restart hint. */
    val addContext: Boolean = false,
    val optInDialogOpen: Boolean = false,
    /** The deliberate B acknowledgment (the opt-in act) — a step, never a default. */
    val riskAcknowledged: Boolean = false,
    val error: String? = null,
) {
    /** B confirm needs BOTH the operator gate and the explicit acknowledgment — visible before the action. */
    val canConfirm: Boolean get() = editable && riskAcknowledged

    /**
     * Edit context only (CYP-126): a change from the agent's current connector → surface the reused amber
     * `agent_edit_effect_hint` ("saved ≠ active — restart"). Never in add context (a fresh spawn carries the
     * kind, no restart). The picker is INTENT — it never claims B is active (the active connector is the
     * server-truth capability display only).
     */
    val showEffectHint: Boolean get() = !addContext && draftKind != initialKind
}

/**
 * Drives the per-agent connector selection (CYP-123 / CYP-126, spec §3) over a [ConnectorSelectionRepository]
 * (the live [ConnectorSelectionHttpRepository] after the CYP-126 swap; the stub in tests). The security state
 * machine, mirroring CYP-93:
 *
 *  - **A (stream-json)** is the plain default — [selectKind] with `STREAM_JSON` settles it immediately (no
 *    acknowledgment needed) and never opens a dialog.
 *  - **B (MCP)** is a deliberate opt-in — [selectKind] with `MCP` does NOT set the connector; it opens the
 *    opt-in dialog. The draft only becomes MCP via [confirmOptIn], and only when [ConnectorSelectionUiState.canConfirm]
 *    (operator-gated AND acknowledged). This is the **only** path to B.
 *
 * **Two bind contexts (CYP-126):**
 *  - **Edit** (existing agent, [agentId] set, [onKindChosen] null): a settled kind is written to the dedicated
 *    operator-gated connector endpoint via [repository] (`POST /api/agents/{id}/connector`). [initialKind] is
 *    the agent's current connector so an existing B agent shows B selected (the truth) without a fresh opt-in.
 *  - **Add** (no agent yet, [onKindChosen] non-null): a settled kind is captured into `NewAgentSpec.connectorKind`
 *    via the callback — there is **no** connector-endpoint call (the create carries it). B at creation still
 *    goes through the same ack-gated opt-in dialog; only the commit target differs.
 *
 * **Anti-injection invariant (spec §3.3/§5, load-bearing):** there is deliberately NO method that activates a
 * connector from message/agent/external input — the only entry points are [selectKind] (operator UI) and
 * [confirmOptIn] (operator, ack-gated). Channel/agent content is untrusted data, never an instruction here.
 */
class ConnectorSelectionViewModel(
    private val repository: ConnectorSelectionRepository,
    private val agentId: String?,
    editable: Boolean = false,
    initialKind: ConnectorKind = ConnectorKind.STREAM_JSON,
    /**
     * Add context only: notified with the settled kind so the add-form fills `NewAgentSpec.connectorKind`. When
     * non-null the connector endpoint is NOT called (the create carries the kind) and the edit-only restart hint
     * is suppressed. Null = edit/standalone context → the write goes via [repository] (the connector endpoint).
     */
    private val onKindChosen: ((ConnectorKind) -> Unit)? = null,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope
    private val addContext: Boolean = onKindChosen != null
    private val _state = MutableStateFlow(
        ConnectorSelectionUiState(
            editable = editable,
            draftKind = initialKind,
            initialKind = initialKind,
            addContext = addContext,
        ),
    )
    val state: StateFlow<ConnectorSelectionUiState> = _state.asStateFlow()

    /**
     * The operator picked a connector kind in the picker. A is settled immediately (no ack); B opens the
     * deliberate opt-in dialog (the draft does NOT become B yet). Fail-closed without the operator gate.
     */
    fun selectKind(kind: ConnectorKind) {
        if (!_state.value.editable) return // fail-closed (server also enforces)
        when (kind) {
            ConnectorKind.STREAM_JSON -> {
                // A is the plain, first-class default — settle immediately, no dialog, no ack.
                _state.update { it.copy(draftKind = ConnectorKind.STREAM_JSON, optInDialogOpen = false, error = null) }
                settle(ConnectorSelection(ConnectorKind.STREAM_JSON, riskAcknowledged = false))
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
     * on failure the error key is set and the draft stays unchanged (no half-activation).
     *
     * Add context (callback set): the kind is captured into the spec — no endpoint, settles synchronously.
     * Edit context: the acknowledged B is written to the dedicated connector endpoint via [repository].
     */
    fun confirmOptIn() {
        val s = _state.value
        if (!s.canConfirm) return // fail-closed: operator gate + deliberate acknowledgment (server re-checks)
        val cb = onKindChosen
        if (cb != null) {
            // Add context: B rides NewAgentSpec; no connector-endpoint call. The gate (ack) was still required.
            _state.update {
                it.copy(draftKind = ConnectorKind.MCP, optInDialogOpen = false, riskAcknowledged = false, error = null)
            }
            cb(ConnectorKind.MCP)
            return
        }
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

    /** Cancel the opt-in: close the dialog, reset the ack, leave the draft unchanged. */
    fun cancelOptIn() = _state.update { it.copy(optInDialogOpen = false, riskAcknowledged = false, error = null) }

    /**
     * Commit a settled selection. Add context (callback set): hand the kind to the add-form, no endpoint. Edit
     * context: write to the connector endpoint via [repository]. The only callers are [selectKind] (A) and
     * [confirmOptIn] (B) — never external input (anti-injection).
     */
    private fun settle(selection: ConnectorSelection) {
        val cb = onKindChosen
        if (cb != null) {
            cb(selection.kind) // add context: captured into NewAgentSpec, no connector-endpoint call
            return
        }
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
