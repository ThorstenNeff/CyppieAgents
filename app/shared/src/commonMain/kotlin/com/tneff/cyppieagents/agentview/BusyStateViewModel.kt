package com.tneff.cyppieagents.agentview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * CYP-324 — shell-level holder for the per-agent busy map that feeds the window title-bar `*`. ONE subscription to
 * [BusyStateSource.events] (one `/ws/busy-state` socket for all agents), upserted by `agentId` into a [busy] map.
 * **Latest-wins / idempotent:** each event overwrites that agent's entry — a reconnect snapshot never duplicates
 * or loses (a reconnect mid-turn re-delivers `busy = true`, so the `*` is restored, not doubled).
 *
 * **Honesty (unknown ≠ busy):** an agent with no event yet is simply absent from the map; the title-bar lookup
 * returns `false` for it, so the `*` is ABSENT — an unseen agent never fabricates a busy marker. Only an explicit
 * `busy = true` event lights the `*`; an explicit `false` clears it.
 */
class BusyStateViewModel(
    private val source: BusyStateSource,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope

    private val _busy = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val busy: StateFlow<Map<String, Boolean>> = _busy.asStateFlow()

    init {
        runScope.launch {
            source.events().collect { event ->
                // Upsert by agentId (latest-wins) — never append; a re-delivered snapshot is idempotent.
                _busy.update { it + (event.agentId to event.busy) }
            }
        }
    }
}
