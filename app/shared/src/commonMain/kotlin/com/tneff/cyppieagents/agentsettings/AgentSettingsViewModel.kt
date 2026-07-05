package com.tneff.cyppieagents.agentsettings

import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tneff.cyppieagents.agentmgmt.AgentManagementRepository
import com.tneff.cyppieagents.model.AgentAvatar
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.parseHexColor
import com.tneff.cyppieagents.ui.SenderPalette
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The declared effective fallback stage (§4) for QA-3's deterministic assertion on `avatar.current` — derived from
 * the DTO, NOT a pixel peek: Upload→IMAGE, Preset→PRESET, else name→INITIALS, blank name→COLOR.
 */
enum class AvatarStage { IMAGE, PRESET, INITIALS, COLOR }

/** A client-side upload pre-check failure (the server is authoritative; this is the honest first hint). */
enum class AvatarUploadError { TYPE, SIZE, GENERIC }

/** Immutable UI state for the per-agent settings panel (CYP-211 + CYP-216 avatar). */
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
    // --- CYP-216 avatar ---
    /** The EFFECTIVE avatar (server truth): prefilled from detail, replaced ONLY by a server response (#3). */
    val avatar: AgentAvatar? = null,
    /** Which preset style the grid shows as selected (read-only reflection of a Preset avatar; null for Upload/none). */
    val selectedStyle: String? = null,
    val avatarBusy: Boolean = false,
    val avatarError: AvatarUploadError? = null,
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
    /** The declared effective avatar stage (§4) — for QA-3 (`agentSettings.avatar.current` stateDescription). */
    val effectiveStage: AvatarStage
        get() = when {
            avatar is AgentAvatar.Upload -> AvatarStage.IMAGE
            avatar is AgentAvatar.Preset -> AvatarStage.PRESET
            name.isNotBlank() -> AvatarStage.INITIALS
            else -> AvatarStage.COLOR
        }
}

/**
 * CYP-211/216 — drives the per-agent settings panel. Prefills name/colour/persona/avatar from
 * [AgentManagementRepository.detail] (`GET /api/agents/{id}`), saves name+colour+persona through the SAME edit path
 * ([AgentManagementRepository.edit], blank→PRESERVE) — no second editor (spec §1). `id` is never sent (immutable).
 *
 * **Avatar (CYP-216), security by construction:** a PRESET is a FRESH [AgentAvatar.Preset] built here and written via
 * the SAME `edit` path (Preset-only, contract-enforced) — a prefilled [AgentAvatar.Upload] is NEVER cast/replayed into
 * a write (#3). An UPLOAD goes through the multipart endpoint after a client PNG/JPG + size pre-check; either way the
 * new [avatar] state comes from the SERVER response (upload → `AgentDetail`, preset → the edited `Agent`), never the
 * local file-pick. Fail-closed: not [editable] → every setter/action is a no-op.
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
            if (d == null) {
                it.copy(loading = false)
            } else {
                it.copy(
                    loading = false,
                    name = d.name,
                    role = d.role,
                    persona = d.persona ?: "",
                    activePersona = d.persona ?: "",
                    savedPersona = d.persona ?: "",
                    colorHex = d.color?.takeIf { c -> c.isNotBlank() } ?: it.colorHex,
                    avatar = d.avatar,
                    // Read-only reflection for the grid selection ring; `as?` → null for an Upload (no style selected).
                    selectedStyle = (d.avatar as? AgentAvatar.Preset)?.style,
                )
            }
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

    /** Pick a DiceBear preset — a FRESH Preset(style, seed = agentId), written via the Preset-only edit path. */
    fun selectPreset(style: String) = ifEditable {
        writeAvatarPreset(AgentAvatar.Preset(style = style, seed = agentId))
    }

    /** Shuffle = re-roll the seed within the selected style → a different avatar of the SAME style (§3.2, MVP). */
    fun shuffle() = ifEditable {
        val style = _state.value.selectedStyle ?: return@ifEditable
        writeAvatarPreset(AgentAvatar.Preset(style = style, seed = "$agentId-${Random.nextInt(1_000_000)}"))
    }

    private fun writeAvatarPreset(preset: AgentAvatar.Preset) {
        _state.update { it.copy(avatarBusy = true, avatarError = null) }
        runScope.launch {
            runCatching { repository.edit(agentId, AgentEdit(role = _state.value.role, avatar = preset)) }
                .onSuccess { agent -> applyServerAvatar(agent.avatar) }
                .onFailure { _state.update { it.copy(avatarBusy = false, avatarError = AvatarUploadError.GENERIC) } }
        }
    }

    /**
     * Upload a custom image. Client pre-check (PNG/JPG only, size ceiling) is the honest first hint; the SERVER
     * validates authoritatively and its `AgentDetail` response is the source of truth for the new avatar (#3).
     */
    fun uploadAvatar(bytes: ByteArray, filename: String, mimeType: String) = ifEditable {
        val mime = mimeType.lowercase()
        if (mime !in ALLOWED_UPLOAD_MIME) {
            _state.update { it.copy(avatarError = AvatarUploadError.TYPE) }
            return@ifEditable
        }
        if (bytes.size > MAX_UPLOAD_BYTES) {
            _state.update { it.copy(avatarError = AvatarUploadError.SIZE) }
            return@ifEditable
        }
        _state.update { it.copy(avatarBusy = true, avatarError = null) }
        runScope.launch {
            runCatching { repository.uploadAvatar(agentId, bytes, filename, mime) }
                .onSuccess { detail -> applyServerAvatar(detail.avatar) }
                .onFailure { _state.update { it.copy(avatarBusy = false, avatarError = AvatarUploadError.GENERIC) } }
        }
    }

    /** Clear back to the default (initials/colour) — confirmed by the server (DELETE → 204). */
    fun clearAvatar() = ifEditable {
        _state.update { it.copy(avatarBusy = true, avatarError = null) }
        runScope.launch {
            runCatching { repository.clearAvatar(agentId) }
                .onSuccess { _state.update { it.copy(avatarBusy = false, avatar = null, selectedStyle = null) } }
                .onFailure { _state.update { it.copy(avatarBusy = false, avatarError = AvatarUploadError.GENERIC) } }
        }
    }

    /** Adopt the SERVER's avatar truth (never the local pick) — the one place "avatar set" state is written (#3). */
    private fun applyServerAvatar(serverAvatar: AgentAvatar?) = _state.update {
        it.copy(avatarBusy = false, avatar = serverAvatar, selectedStyle = (serverAvatar as? AgentAvatar.Preset)?.style)
    }

    private inline fun ifEditable(block: () -> Unit) { if (_state.value.editable) block() }

    companion object {
        /** Client pre-check allow-list — PNG/JPG only (no webp: no server decoder; no SVG: XSS). Server is authoritative. */
        val ALLOWED_UPLOAD_MIME = setOf("image/png", "image/jpeg", "image/jpg")
        /** Coarse client size ceiling (the server enforces the real limit); `MAX_UPLOAD_LABEL` fills the error `%1$s`. */
        const val MAX_UPLOAD_BYTES = 5 * 1024 * 1024
        const val MAX_UPLOAD_LABEL = "5 MB"
    }
}
