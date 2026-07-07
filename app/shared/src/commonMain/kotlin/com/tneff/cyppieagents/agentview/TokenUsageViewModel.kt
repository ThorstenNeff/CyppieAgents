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
 * CYP-316 — shell-level holder for the per-agent context-token map that feeds the window title bars. ONE
 * subscription to [TokenUsageSource.events] (one `/ws/token-usage` socket for all agents), upserted by `agentId`
 * into a [tokens] map. **Latest-wins / idempotent:** each event overwrites that agent's entry — a reconnect
 * snapshot never duplicates or loses. A `null` `contextTokens` is stored AS `null` (unknown), so the title bar's
 * lookup returns `null` and the number is ABSENT — never `0` (the §8-3 honesty core: `null ≠ 0`).
 *
 * The [tokens] value for a given id is `null` both when unseen and when the last event carried `null` — both mean
 * "no trustworthy value" → show nothing, so the display treats them identically (correct by construction).
 */
class TokenUsageViewModel(
    private val source: TokenUsageSource,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope

    private val _tokens = MutableStateFlow<Map<String, Int?>>(emptyMap())
    val tokens: StateFlow<Map<String, Int?>> = _tokens.asStateFlow()

    init {
        runScope.launch {
            source.events().collect { event ->
                // Upsert by agentId (latest-wins) — never append; a re-delivered snapshot is idempotent.
                _tokens.update { it + (event.agentId to event.contextTokens) }
            }
        }
    }
}
