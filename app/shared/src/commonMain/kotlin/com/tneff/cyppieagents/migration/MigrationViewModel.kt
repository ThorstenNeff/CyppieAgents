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
 * CYP-220 / CYP-727 — the Migrations-Screen state holder. Loads the per-project store inventory (§2) and history
 * (§8) from the injected [MigrationApi] (stub now, live CYP-723 later).
 *
 * **CYP-727 (safe-but-silent §4a fix):** the inventory is an explicit [InventoryState] — `Checking` (query in
 * flight), `Unavailable` (the query **failed**), or `Known` (loaded, whether empty or populated). The old
 * `loading:Boolean + stores:MigrationStores?` collapsed all three onto one header-only render:
 * `runCatching{}.getOrNull()` turned a failed query into a silent `null`, byte-identical to a genuinely-empty
 * inventory — a "nothing to migrate" **lie** on the anti-lie surface. A failed load is now honestly distinct
 * (→ the section offers a retry), never a fabricated empty.
 */
class MigrationViewModel(
    private val api: MigrationApi,
    private val projectId: String,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(MigrationUiState(inventory = InventoryState.Checking))
    val state: StateFlow<MigrationUiState> = _state.asStateFlow()

    init { load() }

    /**
     * (Re)load the inventory + history. Idempotent. A stores-query failure yields [InventoryState.Unavailable]
     * (NOT a silent empty) so the section offers a retry instead of falsely claiming "nothing to migrate".
     */
    fun load() {
        _state.update { it.copy(inventory = InventoryState.Checking) }
        runScope.launch {
            val inventory = runCatching { api.stores(projectId) }
                .fold(
                    onSuccess = { InventoryState.Known(it) },
                    onFailure = { InventoryState.Unavailable },
                )
            // History keeps its own §8 two-empty-states model (RECORDING vs UNAVAILABLE); a history-fetch failure
            // is the honest "unavailable" absence (null → the §8 renderer shows "not recorded"). It does NOT fail
            // the whole inventory section — that axis is out of CYP-727's scope (inventory only).
            val history = runCatching { api.history(projectId) }.getOrNull()
            _state.update { MigrationUiState(inventory = inventory, history = history) }
        }
    }
}

/**
 * The Migrations-Screen UI state. [inventory] is an explicit tri-state (CYP-727); [history] is `null` until loaded
 * (honest absence, never a fabricated empty). The screen shows **no aggregate** — there is no "N of M migrated"
 * here (spec §2.3).
 */
data class MigrationUiState(
    val inventory: InventoryState,
    val history: MigrationHistory? = null,
)

/**
 * CYP-727 — the inventory load tri-state, kept DISTINCT so the section never renders a failed query as an empty
 * one (the safe-but-silent §4a gap). Render precedence in `MigrationSection` is **loading → error → empty →
 * content** (the CYP-288 house rule): a failed load always beats an empty one.
 */
sealed interface InventoryState {
    /** The stores query is in flight (first paint / reload). */
    data object Checking : InventoryState

    /** The stores query **failed** — the section shows a retry, never a false "nothing to migrate". */
    data object Unavailable : InventoryState

    /** The stores query succeeded; [stores] may be genuinely empty (nothing to migrate) or populated. */
    data class Known(val stores: MigrationStores) : InventoryState
}
