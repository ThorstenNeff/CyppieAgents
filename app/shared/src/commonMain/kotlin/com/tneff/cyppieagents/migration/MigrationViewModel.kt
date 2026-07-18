package com.tneff.cyppieagents.migration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * CYP-220 — the Migrations-Screen state holder. Loads the per-project store inventory (§2) and history (§8) from
 * the injected [MigrationApi] (stub now, live CYP-723 later). Pure data + honest absence: [MigrationUiState.stores]
 * is `null` while loading (the UI shows nothing rather than a fabricated empty inventory — the §2.3 "no total"
 * discipline extends to load). No live migration is driven here in the stub increment; §4–§7 actions land later.
 */
class MigrationViewModel(
    private val api: MigrationApi,
    private val projectId: String,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(MigrationUiState(loading = true))
    val state: StateFlow<MigrationUiState> = _state.asStateFlow()

    init { load() }

    /** (Re)load the inventory + history. Idempotent; a failure leaves the honest empty (loaded, no data). */
    fun load() {
        _state.update { it.copy(loading = true) }
        runScope.launch {
            val stores = runCatching { api.stores(projectId) }.getOrNull()
            val history = runCatching { api.history(projectId) }.getOrNull()
            _state.update { MigrationUiState(loading = false, stores = stores, history = history) }
        }
    }
}

/**
 * The Migrations-Screen UI state. [stores]/[history] are `null` until loaded (honest absence, never a fabricated
 * empty). The screen shows **no aggregate** — there is no "N of M migrated" here (spec §2.3).
 */
data class MigrationUiState(
    val loading: Boolean,
    val stores: MigrationStores? = null,
    val history: MigrationHistory? = null,
)
