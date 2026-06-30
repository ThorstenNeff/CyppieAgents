package com.tneff.cyppieagents.report
import com.tneff.cyppieagents.model.ReportType
import com.tneff.cyppieagents.model.ReportSnapshot
import com.tneff.cyppieagents.model.ReportMeta

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
 * Immutable UI state for the Product-Lead panel (S16). [accessible] is the operator gate: without it
 * there is no list, no trigger, no detail — only the gate hint (fail-closed; the VM never even loads).
 * [selected] is a chosen snapshot's full content; [generating] guards the on-demand trigger.
 */
data class ProductLeadUiState(
    val loading: Boolean = true,
    /** Operator token present → trigger + list + detail; else gate hint only (fail-closed, no report). */
    val accessible: Boolean = false,
    val metas: List<ReportMeta> = emptyList(),
    val selectedId: String? = null,
    val selected: ReportSnapshot? = null,
    val generating: Boolean = false,
    /** i18n key for an error (`report_access_denied` / `report_error`), or null. */
    val error: String? = null,
)

/**
 * Drives the Product-Lead panel (S16, CYP-90) over a [ReportRepository] (stub today; live REST after
 * the CYP-89 backend seam — no VM/UI change). On-demand only: a trigger generates a NEW immutable
 * snapshot (never mutating a prior one) and selects it; the list is a newest-first history. **Fail-closed
 * around the operator-gated observability:** with no access the VM neither lists nor generates — a viewer
 * gets no report at all (not a partial), and the items are content-free (PRODUCT-LEAD §1.4).
 */
class ProductLeadViewModel(
    private val repository: ReportRepository,
    accessible: Boolean = false,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow(ProductLeadUiState(accessible = accessible))
    val state: StateFlow<ProductLeadUiState> = _state.asStateFlow()

    init {
        // Fail-closed: only an operator load lists reports. No access → no load, no (partial) report.
        if (accessible) runScope.launch { reloadList() } else _state.update { it.copy(loading = false) }
    }

    private suspend fun reloadList() {
        val metas = runCatching { repository.list() }.getOrDefault(emptyList())
        _state.update { it.copy(metas = metas, loading = false) }
    }

    fun select(id: String) {
        if (!_state.value.accessible) return
        _state.update { it.copy(selectedId = id, error = null) }
        runScope.launch {
            runCatching { repository.get(id) }
                .onSuccess { snap -> _state.update { if (it.selectedId != id) it else it.copy(selected = snap) } }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(error = errorKey(e)) }
                }
        }
    }

    fun generate(type: ReportType) {
        // Fail-closed gate + no double-trigger while one is in flight.
        if (!_state.value.accessible || _state.value.generating) return
        _state.update { it.copy(generating = true, error = null) }
        runScope.launch {
            runCatching { repository.generate(type) }
                .onSuccess { snap ->
                    reloadList()
                    // Select the fresh snapshot — a new run, never an overwrite of a prior one.
                    _state.update { it.copy(generating = false, selectedId = snap.id, selected = snap) }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(generating = false, error = errorKey(e)) }
                }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun errorKey(e: Throwable): String = when ((e as? ReportException)?.code) {
        "operator_required", "unauthorized" -> "report_access_denied"
        else -> "report_error"
    }
}
