package com.tneff.cyppieagents.connector

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
 * Immutable UI state for the connector/capability surface (CYP-123). Holds the per-agent connector read-model
 * (for the badges/panels) plus the state of the single connector-**selection** dialog. Fail-closed throughout:
 * no [editable] → every mutation is a no-op; missing info → no faked capability (the badge/panel say so).
 */
data class ConnectorUiState(
    /** Operator token present → selection enabled; else read-only + gate hint (server also enforces). */
    val editable: Boolean = false,
    val infos: Map<String, AgentConnectorInfo> = emptyMap(),
    val loaded: Boolean = false,
    // ── selection dialog (one agent at a time) ───────────────────────────────────────────────────────
    val dialogAgentId: String? = null,
    /**
     * The radio choice in the dialog. [ConnectorKind.MCP] (B) is **never silently the default**: [openSelect]
     * seeds this from the agent's CURRENT kind (A when unknown/new), and [setKind] resets the acknowledgment —
     * so ending up on B always costs a deliberate pick + ack.
     */
    val draftKind: ConnectorKind = ConnectorKind.STREAM_JSON,
    /** The deliberate B opt-in act (a checkbox), never a default; reset whenever the kind changes. */
    val riskAcknowledged: Boolean = false,
    val error: String? = null,
) {
    fun infoFor(agentId: String): AgentConnectorInfo? = infos[agentId]
    fun currentKind(agentId: String): ConnectorKind? = infos[agentId]?.kind
    val dialogOpen: Boolean get() = dialogAgentId != null
    val draftSelection: ConnectorSelection get() = ConnectorSelection(draftKind, riskAcknowledged)
    /** B requires the explicit acknowledgment; A never does. Shown before the action; the server re-checks. */
    val needsAcknowledgment: Boolean get() = draftKind == ConnectorKind.MCP
    /** Confirm needs operator + (for B) the ack — visible before the action, fail-closed, server-authoritative. */
    val canConfirm: Boolean get() = editable && draftSelection.isAcknowledgmentSatisfied
}

/**
 * Drives the connector/capability surface (CYP-123) over a [ConnectorRepository] (stub today; later
 * stub→real swap, no UI change). The B opt-in is a **directed operator act** — there is NO affordance to pick
 * a connector from a message/agent request (anti-injection, mirrors CYP-93 §2.6). The selection write is
 * operator-gated here AND server-side (the server is the authority that enforces + audits the B ack — a
 * client-only gate would be fail-open).
 */
class ConnectorViewModel(
    private val repository: ConnectorRepository,
    editable: Boolean = false,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow(ConnectorUiState(editable = editable))
    val state: StateFlow<ConnectorUiState> = _state.asStateFlow()

    init { runScope.launch { reload() } }

    private suspend fun reload() {
        runCatching { repository.connectorInfos() }
            .onSuccess { m -> _state.update { it.copy(infos = m, loaded = true) } }
            .onFailure { e -> if (e is CancellationException) throw e } // fail-closed: keep prior, no invented caps
    }

    fun openSelect(agentId: String) {
        if (!_state.value.editable) return // operator gate (server also enforces)
        // Seed from the CURRENT kind (honest), defaulting to A when unknown → B is never preselected for a new
        // agent. The ack always resets so switching to/keeping B is a fresh deliberate act.
        val current = _state.value.currentKind(agentId) ?: ConnectorKind.STREAM_JSON
        _state.update { it.copy(dialogAgentId = agentId, draftKind = current, riskAcknowledged = false, error = null) }
    }

    fun setKind(kind: ConnectorKind) =
        // Changing the kind invalidates any prior ack → re-acknowledge for B (fail-closed).
        _state.update { it.copy(draftKind = kind, riskAcknowledged = false, error = null) }

    fun setRiskAcknowledged(ack: Boolean) = _state.update { it.copy(riskAcknowledged = ack, error = null) }

    fun closeDialog() = _state.update { it.copy(dialogAgentId = null, error = null) }

    fun confirmSelection() {
        val s = _state.value
        val agentId = s.dialogAgentId ?: return
        if (!s.canConfirm) return // fail-closed: operator + B-ack guard (the server re-checks + audits)
        runScope.launch {
            runCatching { repository.setConnector(agentId, s.draftSelection) }
                .onSuccess { info ->
                    _state.update {
                        it.copy(
                            infos = it.infos + (agentId to info),
                            dialogAgentId = null,
                            riskAcknowledged = false,
                            error = null,
                        )
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(error = errorKey(e)) }
                }
        }
    }

    private fun errorKey(e: Throwable): String = when ((e as? ConnectorException)?.code) {
        "operator_required", "unauthorized" -> "connector_operator_required"
        "risk_ack_required" -> "connector_risk_required"
        else -> "connector_error"
    }
}
