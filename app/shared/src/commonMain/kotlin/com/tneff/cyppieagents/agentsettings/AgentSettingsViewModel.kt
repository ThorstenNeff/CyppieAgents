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
import kotlinx.coroutines.CancellationException
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
    // --- CYP-310 live CLAUDE.md (replaces the stored-persona field; own GET/POST, decoupled from `save()`) ---
    /** The editable field content (starts == the live file; dirty when it diverges → overwrite becomes enabled). */
    val claudeMd: String = "",
    /** The last LOADED-or-WRITTEN file content (dirty baseline). */
    val claudeMdLoaded: String = "",
    /** Content-hash of the loaded file — sent as `expectedVersion` on overwrite (optimistic concurrency, v3). */
    val claudeMdVersion: String = "",
    /** The live GET is in flight → fail-closed: field + overwrite not yet usable ([PERSONA_LOADING]). */
    val claudeMdLoading: Boolean = true,
    /** The live GET FAILED → **fail-closed** (no content, overwrite locked; distinct from a settled-empty file —
     *  the CYP-288 failed≠empty line; [PERSONA_UNAVAILABLE] LoadErrorRetry surface). */
    val claudeMdError: Boolean = false,
    /** The file exists on disk (from the GET). `false` = absent → a first write CREATES it (not destructive). */
    val claudeMdFileExists: Boolean = false,
    /** A hard overwrite (POST) is in flight. */
    val claudeMdWriting: Boolean = false,
    /** An overwrite succeeded THIS session → the running agent (which read the OLD file) needs a restart → the hint. */
    val claudeMdWritten: Boolean = false,
    /** A non-stale write failure (network/500) — surfaced honestly, distinct from the stale conflict. */
    val claudeMdWriteError: Boolean = false,
    /** 409 `claude_md_stale`: the file changed externally (maybe the agent) → the Layer-2 confirm ([PERSONA_CONFIRM]). */
    val claudeMdStale: Boolean = false,
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
     * The EFFECT_DEFERRED restart hint state (CYP-310): a CLAUDE.md OVERWRITE succeeded this session but the running
     * agent already read the OLD file, so it needs a restart to pick up the change ("Wirkt erst beim nächsten Start").
     * True ONLY **after** a successful overwrite, never while merely editing the field. Name/colour stay immediate.
     */
    val needsRestart: Boolean get() = claudeMdWritten

    // --- CYP-310 derived CLAUDE.md UI states (S1-S6) ---
    /** The field diverges from the loaded file → an overwrite would change something. */
    val claudeMdDirty: Boolean get() = claudeMd != claudeMdLoaded
    /** Settled-EMPTY: the GET succeeded but the file is absent (`exists=false`) — valid, a write CREATES it. Distinct
     *  from [claudeMdError] (load failed → fail-closed). The CYP-288 failed≠empty line ([PERSONA_EMPTY]). */
    val claudeMdEmpty: Boolean get() = !claudeMdLoading && !claudeMdError && !claudeMdFileExists
    /** The overwrite button is enabled ONLY when: operator, the live read settled OK, and the field is dirty.
     *  Fail-closed: disabled while loading / on a read error / mid-write. */
    val canOverwriteClaudeMd: Boolean get() =
        editable && !claudeMdLoading && !claudeMdError && !claudeMdWriting && claudeMdDirty
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
    /** CYP-310: the live worktree CLAUDE.md port. Read on open (fail-closed), overwritten via the explicit button.
     *  Defaults to the in-memory [StubClaudeMdApi]; production wires [ClaudeMdHttpApi] at the shell. */
    private val claudeMdApi: ClaudeMdApi = StubClaudeMdApi(),
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

    init {
        runScope.launch { load() }
        // CYP-310: the CLAUDE.md field is the LIVE worktree file (its own GET), no longer `d.persona` from detail.
        loadClaudeMd()
    }

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
                    colorHex = d.color?.takeIf { c -> c.isNotBlank() } ?: it.colorHex,
                    avatar = d.avatar,
                    // Read-only reflection for the grid selection ring; `as?` → null for an Upload (no style selected).
                    selectedStyle = (d.avatar as? AgentAvatar.Preset)?.style,
                )
            }
        }
    }

    /**
     * CYP-310: read the LIVE worktree CLAUDE.md (`GET /api/agents/{id}/claude-md`). **Fail-closed** — a load
     * failure leaves `claudeMdError = true` (no content shown, overwrite locked), NEVER a stale/guessed value; a
     * settled-absent file is the distinct EMPTY state (`exists=false`). Also used by "Live neu laden" (reload).
     */
    fun loadClaudeMd() {
        _state.update { it.copy(claudeMdLoading = true, claudeMdError = false, claudeMdStale = false, claudeMdWriteError = false) }
        runScope.launch {
            runCatching { claudeMdApi.get(agentId) }
                .onSuccess { v ->
                    _state.update {
                        it.copy(
                            claudeMdLoading = false, claudeMdError = false,
                            claudeMd = v.content, claudeMdLoaded = v.content,
                            claudeMdVersion = v.version, claudeMdFileExists = v.exists,
                        )
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(claudeMdLoading = false, claudeMdError = true) } // fail-closed
                }
        }
    }

    /**
     * CYP-237: [AgentSettingsUiState.saved] is a ONE-SHOT close signal — the host consumes it (resets to false)
     * right after acting on it. The overlay's VM is RETAINED (`viewModel(key="agentSettings-$id")` survives the
     * close), so a sticky `saved=true` would make the panel's `LaunchedEffect(saved)` re-fire `onSaved` at first
     * composition when the SAME agent is reopened → an instant-close race (the user couldn't reopen the just-edited
     * agent until reload). Consuming it makes every real save a fresh false→true edge.
     */
    fun consumeSaved() { _state.update { it.copy(saved = false) } }

    fun setName(v: String) = ifEditable { _state.update { it.copy(name = v, saved = false) } }
    fun setColorHex(v: String) = ifEditable { _state.update { it.copy(colorHex = v, saved = false) } }
    fun pickSwatch(hex: String) = ifEditable { _state.update { it.copy(colorHex = hex, saved = false) } }
    /** CYP-310: edit the local CLAUDE.md buffer. Typing while dirty FREEZES the live-refresh (D1) — the buffer is
     *  never clobbered by a background reload — and clears a prior save-error (a fresh attempt). Not auto-saved. */
    fun setClaudeMd(v: String) = ifEditable { _state.update { it.copy(claudeMd = v, claudeMdWriteError = false) } }

    fun save() {
        val s = _state.value
        if (!s.editable || s.hexError) return
        _state.update { it.copy(saving = true, error = false) }
        runScope.launch {
            // CYP-310: `save()` writes ONLY name+colour now — the CLAUDE.md moves to its own live GET + explicit
            // "Überschreiben" (POST); persona is no longer part of the shared edit path (null → PRESERVE, decoupled).
            val edit = AgentEdit(
                role = s.role,
                name = s.name.ifBlank { null },
                color = s.colorHex.ifBlank { null },
            )
            runCatching { repository.edit(agentId, edit) }
                .onSuccess { _state.update { it.copy(saving = false, saved = true) } }
                .onFailure { _state.update { it.copy(saving = false, error = true) } }
        }
    }

    /**
     * CYP-310: HARD-overwrite the live CLAUDE.md with the buffer (`POST …/claude-md`, `expectedVersion` = the read
     * base). Fail-closed: no-op unless operator + settled read + dirty. On **200** → dirty→clean, re-sync to the
     * server echo, and the restart hint (Hop ②: file ≠ running agent). On **409 stale** → the Layer-2 conflict
     * dialog (§5). On any other failure → S6 (stays dirty, NO fake "saved"). First-write on an absent file just
     * creates it (no confirm — nothing destroyed).
     */
    fun overwriteClaudeMd() {
        val s = _state.value
        if (!s.canOverwriteClaudeMd) return
        writeClaudeMd(s.claudeMd, s.claudeMdVersion)
    }

    /** Layer-2 "[Trotzdem überschreiben]": force the write over the external change — re-fetch the CURRENT version,
     *  then overwrite with the LOCAL buffer against it (the user chose to discard the external edit). */
    fun forceOverwriteClaudeMd() {
        val s = _state.value
        if (!s.editable || s.claudeMdWriting) return
        val content = s.claudeMd
        _state.update { it.copy(claudeMdStale = false, claudeMdWriting = true) }
        runScope.launch {
            val fresh = runCatching { claudeMdApi.get(agentId) }.getOrNull()
            if (fresh == null) {
                _state.update { it.copy(claudeMdWriting = false, claudeMdError = true) } // fail-closed: unknown base
                return@launch
            }
            doWrite(content, fresh.version)
        }
    }

    /** Layer-2 "[Live-Version laden]": discard the local buffer and reload the live file (dirty edits were flagged). */
    fun reloadLiveClaudeMd() {
        _state.update { it.copy(claudeMdStale = false) }
        loadClaudeMd()
    }

    private fun writeClaudeMd(content: String, expectedVersion: String) {
        _state.update { it.copy(claudeMdWriting = true, claudeMdWriteError = false, claudeMdStale = false) }
        runScope.launch { doWrite(content, expectedVersion) }
    }

    private suspend fun doWrite(content: String, expectedVersion: String) {
        runCatching { claudeMdApi.update(agentId, content, expectedVersion) }
            .onSuccess { v ->
                // dirty→clean: re-sync the buffer + baseline to the SERVER echo; arm the restart hint (Hop ②).
                _state.update {
                    it.copy(
                        claudeMdWriting = false,
                        claudeMd = v.content, claudeMdLoaded = v.content,
                        claudeMdVersion = v.version, claudeMdFileExists = v.exists,
                        claudeMdWritten = true, claudeMdWriteError = false, claudeMdStale = false,
                    )
                }
            }
            .onFailure { e ->
                if (e is CancellationException) throw e
                when {
                    e is ClaudeMdException && e.code == "claude_md_stale" ->
                        _state.update { it.copy(claudeMdWriting = false, claudeMdStale = true) } // → Layer-2 confirm
                    else ->
                        _state.update { it.copy(claudeMdWriting = false, claudeMdWriteError = true) } // S6: stays dirty
                }
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
