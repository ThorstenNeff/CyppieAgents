package com.tneff.cyppieagents.agentview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tneff.cyppieagents.model.AgentTerminalControlEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * CYP-354 (client mirror) — shell-level holder for the per-agent terminal-control map that will feed the window
 * read-only mode marker. ONE subscription to [TerminalControlSource.events] (one `/ws/terminal-state` socket for
 * all agents), upserted by `agentId` into a [states] map. The whole [AgentTerminalControlEvent] is retained (not
 * just the enum) so the marker can render holder id + since-time when the state is INTERACTIVE/HANDING_*.
 *
 * **Latest-wins / idempotent:** each event overwrites that agent's entry — a reconnect snapshot never duplicates
 * or loses (a reconnect while INTERACTIVE re-delivers INTERACTIVE, so the marker is restored, not doubled).
 *
 * **Honesty (absent == MEDIATED, the contract default):** an agent with no event yet is simply absent from the
 * map. The window lookup treats absent as MEDIATED (the default) → the mode marker is ABSENT (MEDIATED is the
 * normal state, shown without chrome, exactly as an absent CYP-324 busy event == idle → no `*`). Only an
 * explicit non-MEDIATED state lights a marker; an explicit MEDIATED clears it.
 */
class TerminalControlStateViewModel(
    private val source: TerminalControlSource,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope

    private val _states = MutableStateFlow<Map<String, AgentTerminalControlEvent>>(emptyMap())
    val states: StateFlow<Map<String, AgentTerminalControlEvent>> = _states.asStateFlow()

    init {
        runScope.launch {
            source.events().collect { event ->
                // Upsert by agentId (latest-wins) — never append; a re-delivered snapshot is idempotent.
                _states.update { it + (event.agentId to event) }
            }
        }
    }
}
