package com.tneff.cyppieagents.agentview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the agent window: collects the [AgentSession] event stream, folds it into the rendered
 * transcript via [foldEvent], and forwards human turns to the session.
 *
 * Folding logic lives in [foldEvent] (pure, unit-tested); this class only wires it to the stream.
 */
class AgentViewModel(
    private val session: AgentSession,
) : ViewModel() {

    private val _transcript = MutableStateFlow<List<AgentEvent>>(emptyList())
    val transcript: StateFlow<List<AgentEvent>> = _transcript.asStateFlow()

    /**
     * Agent overall status (CYP-12 "Ebene B") derived from the transcript. WAITING_FOR_INPUT /
     * OFFLINE are not derived here (see [deriveStatus]); they come from explicit signals / the
     * session layer.
     */
    val status: StateFlow<AgentStatus> = _transcript
        .map { deriveStatus(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentStatus.IDLE)

    init {
        viewModelScope.launch {
            session.events.collect { event ->
                _transcript.update { foldEvent(it, event) }
            }
        }
    }

    /** A human turn: posted to the agent's channel via the Hub-mediated session (never stdin). */
    fun onSend(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        session.sendMessage(trimmed)
    }
}
