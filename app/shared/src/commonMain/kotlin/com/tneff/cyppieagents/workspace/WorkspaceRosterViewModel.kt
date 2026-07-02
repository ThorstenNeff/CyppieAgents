package com.tneff.cyppieagents.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tneff.cyppieagents.model.WorkspaceMember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * CYP-186 (roster fold) — loads the OPERATOR-only roster once. Constructed only when the user is an OPERATOR
 * (§3.2 structural omission), so it never runs for a MEMBER. Fail-closed: a load error resolves to an empty
 * list (the panel then shows just the title, never a partial/stale roster).
 */
class WorkspaceRosterViewModel(
    private val repository: WorkspaceRepository,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope

    private val _members = MutableStateFlow<List<WorkspaceMember>>(emptyList())
    val members: StateFlow<List<WorkspaceMember>> = _members.asStateFlow()

    init {
        runScope.launch {
            _members.value = runCatching { repository.members() }
                .getOrElse { e -> if (e is CancellationException) throw e; emptyList() }
        }
    }
}
