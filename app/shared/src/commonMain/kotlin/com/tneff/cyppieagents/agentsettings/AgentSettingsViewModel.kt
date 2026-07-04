package com.tneff.cyppieagents.agentsettings

import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tneff.cyppieagents.agentmgmt.AgentManagementRepository
import com.tneff.cyppieagents.comm.SenderPalette
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.parseHexColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Immutable UI state for the per-agent settings panel (CYP-211). */
data class AgentSettingsUiState(
    val loading: Boolean = true,
    val id: String = "",
    val name: String = "",
    val role: Role = Role.WORKER,
    /** The custom `#RRGGBB` (or a picked swatch's hex); blank = no override → the deterministic slot default. */
    val colorHex: String = "",
    val persona: String = "",
    /** The persona ACTIVE in the running agent (baseline captured at open). A SAVE that moves [savedPersona]
     *  away from this ⇒ saved ≠ active ⇒ restart needed (§4.4 / UX-QA fix: the hint is a POST-save state). */
    val activePersona: String = "",
    /** The persona persisted THIS session (starts == [activePersona]; set on a successful save). */
    val savedPersona: String = "",
    val editable: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: Boolean = false,
) {
    /** A non-blank custom hex that isn't `#RRGGBB` → format error (blocks Save; §4.2). */
    val hexError: Boolean get() = colorHex.isNotBlank() && parseHexColor(colorHex) == null
    /**
     * The EFFECT_DEFERRED restart hint state: a persona was SAVED this session but the agent hasn't restarted, so
     * the stored persona ≠ the active one. The key reads "Gespeichert. Wirkt erst beim nächsten Start" → it is
     * true ONLY **after** a persona-changing save, never while merely editing (UX-QA: pre-save "Gespeichert" is a
     * lie). Name/colour are immediate → never this hint.
     */
    val needsRestart: Boolean get() = savedPersona != activePersona
    /** The effective base ARGB for the live preview + titlebar theming: valid custom hex, else the slot default. */
    val baseArgb: Int
        get() = colorHex.takeIf { it.isNotBlank() }?.let { parseHexColor(it) }
            ?: SenderPalette.forSender(id, role).avatarFill.toArgb()
}

/**
 * CYP-211 — drives the per-agent settings panel. Prefills name/colour/persona from [AgentManagementRepository.detail]
 * (`GET /api/agents/{id}`), and saves ALL of name+colour+persona through the SAME existing edit path
 * ([AgentManagementRepository.edit] → `PUT /api/agents/{id}` `AgentEdit`, blank→PRESERVE) — no second persona editor
 * (spec §1 anti-divergence). `id` is never sent (immutable). Fail-closed: not [editable] → the setters are no-ops.
 */
class AgentSettingsViewModel(
    private val agentId: String,
    private val repository: AgentManagementRepository,
    editable: Boolean,
    initialName: String,
    initialColorHex: String?,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow(
        AgentSettingsUiState(
            id = agentId,
            name = initialName,
            editable = editable,
            colorHex = initialColorHex?.takeIf { it.isNotBlank() } ?: "",
        ),
    )
    val state: StateFlow<AgentSettingsUiState> = _state.asStateFlow()

    init { runScope.launch { load() } }

    private suspend fun load() {
        val d = runCatching { repository.detail(agentId) }.getOrNull()
        _state.update {
            if (d == null) it.copy(loading = false)
            else it.copy(
                loading = false,
                name = d.name,
                role = d.role,
                persona = d.persona ?: "",
                activePersona = d.persona ?: "",
                savedPersona = d.persona ?: "",
                colorHex = d.color?.takeIf { c -> c.isNotBlank() } ?: it.colorHex,
            )
        }
    }

    fun setName(v: String) = ifEditable { _state.update { it.copy(name = v, saved = false) } }
    fun setColorHex(v: String) = ifEditable { _state.update { it.copy(colorHex = v, saved = false) } }
    fun pickSwatch(hex: String) = ifEditable { _state.update { it.copy(colorHex = hex, saved = false) } }
    fun setPersona(v: String) = ifEditable { _state.update { it.copy(persona = v, saved = false) } }

    fun save() {
        val s = _state.value
        if (!s.editable || s.hexError) return
        _state.update { it.copy(saving = true, error = false) }
        runScope.launch {
            val edit = AgentEdit(
                role = s.role,
                name = s.name.ifBlank { null },
                color = s.colorHex.ifBlank { null },
                persona = s.persona.ifBlank { null },
            )
            runCatching { repository.edit(agentId, edit) }
                // Record the persona as SAVED (not restarted) → needsRestart flips true iff it moved off active.
                .onSuccess { _state.update { it.copy(saving = false, saved = true, savedPersona = s.persona) } }
                .onFailure { _state.update { it.copy(saving = false, error = true) } }
        }
    }

    private inline fun ifEditable(block: () -> Unit) { if (_state.value.editable) block() }
}
