package com.tneff.cyppieagents.connector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tneff.cyppieagents.model.Capabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Immutable UI state for the connector **capability display** (CYP-123). Holds the per-agent capability map
 * (from the read port) and which agent's detail panel is open. Fail-closed: an agent absent from
 * [capabilities] has `null` caps ⇒ "not yet reported", never faked as full (spec §0/§5).
 */
data class ConnectorCapabilityUiState(
    val capabilities: Map<String, Capabilities> = emptyMap(),
    /** The agent whose detail panel is open (header-badge click → [openPanel]); `null` = none open. */
    val openPanelAgentId: String? = null,
)

/**
 * Drives the read-only connector capability display (CYP-123, spec §2) over a [ConnectorCapabilityRepository]
 * (stub today; [ConnectorCapabilityHttpRepository] at the swap, no UI change). Fail-closed [reload]: on failure
 * the prior caps are kept and nothing is invented — a missing agent stays `null` ("not yet reported"), never
 * upgraded to full fidelity. The connector **write** (selection/opt-in) is the human's separate increment.
 */
class ConnectorCapabilityViewModel(
    private val repository: ConnectorCapabilityRepository,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow(ConnectorCapabilityUiState())
    val state: StateFlow<ConnectorCapabilityUiState> = _state.asStateFlow()

    init { runScope.launch { reload() } }

    private suspend fun reload() {
        runCatching { repository.capabilities() }
            .onSuccess { caps -> _state.update { it.copy(capabilities = caps) } }
            .onFailure { e -> if (e is CancellationException) throw e } // keep prior; fail-closed (no invented caps)
    }

    /** The caps for [agentId], or `null` when not reported (fail-closed — never a faked "full" default). */
    fun capabilitiesFor(agentId: String): Capabilities? = _state.value.capabilities[agentId]

    fun openPanel(agentId: String) = _state.update { it.copy(openPanelAgentId = agentId) }
    fun closePanel() = _state.update { it.copy(openPanelAgentId = null) }
}
