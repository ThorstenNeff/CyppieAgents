package com.tneff.cyppieagents.acl

import androidx.lifecycle.ViewModel
import com.tneff.cyppieagents.net.Backoff
import com.tneff.cyppieagents.net.reconnecting
import androidx.lifecycle.viewModelScope
import com.tneff.cyppieagents.comm.ConnectionStatus
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.WorkspaceMember
import com.tneff.cyppieagents.workspace.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A pending PO-guardrail confirmation (CYP-19 §6): turning a PO-critical grant — or the operator's own read — off. */
data class LockoutPrompt(
    val channelId: String,
    val agentId: String,
    val dimension: AclDimension,
    val channelName: String,
    /** true → operator removing their own `canRead` (self-blind, [acl_self_blind_warning]); false → PO lockout. */
    val selfBlind: Boolean,
)

enum class PresetPhase { PREVIEW, APPLYING, PARTIAL, RESTORED }

/** Non-atomic "restore hub-and-spoke" progress (CYP-19 §7): N per-entry PUTs, honest partial-failure. */
data class PresetState(val phase: PresetPhase, val total: Int = 0, val done: Int = 0, val failed: Int = 0)

/** Immutable UI state for the ACL-matrix panel (CYP-48 / CYP-19 §8). */
data class AclUiState(
    val channels: List<Channel> = emptyList(),
    val agents: List<Agent> = emptyList(),
    /** CYP-189 — human subjects (roster `identityId`s), grantable per-channel. Loaded ONLY when [editable]
     *  (operator); in the non-operator/partialView tree this stays empty AND the panel omits the human band
     *  entirely (Invariante E — the operator-only roster never leaks through the matrix). */
    val members: List<WorkspaceMember> = emptyList(),
    val entries: List<AclEntry> = emptyList(),
    val connection: ConnectionStatus = ConnectionStatus.CONNECTING,
    val loading: Boolean = true,
    /** Operator-token present → switches; else read-only chips + [acl_partial_view] banner (§4). */
    val editable: Boolean = true,
    /** Cell keys (channelId|agentId) with an in-flight PUT — rendered `acl_pending`, not enforced (§5.1). */
    val pending: Set<String> = emptySet(),
    /** Per-cell server-protection notice (cellKey → i18n key, e.g. acl_po_protected) → `.protected` qualifier. */
    val cellNotice: Map<String, String> = emptyMap(),
    /** Global banner notice key (acl_operator_required / acl_unauthorized / acl_change_failed). */
    val notice: String? = null,
    /** Open PO-guardrail consequence dialog (§6b/§6c), or null. */
    val lockoutPrompt: LockoutPrompt? = null,
    /** Preset restore flow state (§7), or null. */
    val preset: PresetState? = null,
    /** Operator token revoked at runtime → honestly devalue, don't leave stale-editable (§8). */
    val accessRevoked: Boolean = false,
)

/**
 * Drives the ACL-matrix panel (CYP-48 / Spec 03 S7, design CYP-19): loads channels+agents+entries over
 * [AclApi], folds the live [AclLiveSource] (`/ws/comm` AclEvent) into a **live mirror of the enforced
 * hub state**, and applies operator toggles **optimistically but not enforced until the hub echoes an
 * `AclEvent`** (the source of truth — NOT the PUT-200, CYP-19 §5.3). A rejected PUT reverts to the hub
 * state and surfaces an honest notice (401/403/409 mapped, §5.4); a missing echo can't hang a cell
 * (pending timeout, §5.4). The PO-lockout guardrail here is **advisory** — the server (CYP-49) enforces.
 *
 * Deferred (PO-tracked, CYP-48 scope): the agent-token-backed read-only partial path (§4). The MVP shell
 * runs an operator context, so [editable] is wired from the operator token; the agent-token repo lands later.
 * When it does, [AclApi.acl] over an agent token MUST return only the viewer's own channels (the server
 * already does this) — otherwise the "only your channels" partial-view banner would misrepresent the data.
 */
class AclViewModel(
    private val api: AclApi,
    private val liveSource: AclLiveSource,
    /** Whether the viewer holds an operator token (editable matrix vs read-only partial view, §4). */
    editable: Boolean = true,
    /** CYP-189 — the operator-only human roster (`GET /api/workspace/members`, BE3a). Consulted ONLY when
     *  [editable]; null (or non-operator) → no human subjects (Invariante E: never fetched as a non-operator). */
    private val workspaceRepository: WorkspaceRepository? = null,
    /** CYP-289 — reconnect backoff for the live `/ws/comm` ACL stream; injectable so tests drive fast reconnects.
     *  Mirrors CommViewModel so ACL auto-heals on a socket drop (was previously honest-but-inert: stale forever). */
    private val backoff: Backoff = Backoff(),
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow(AclUiState(editable = editable))
    val state: StateFlow<AclUiState> = _state.asStateFlow()

    /** CYP-289: the live-collect job, cancelled on a terminal AccessRevoked so the reconnect loop stops. */
    private var liveJob: Job? = null

    init {
        runScope.launch { load() }
        liveJob = runScope.launch { collectLive() }
    }

    private suspend fun load() {
        val channels = runCatching { api.channels() }.getOrDefault(emptyList())
        val agents = runCatching { api.agents() }.getOrDefault(emptyList())
        val entries = runCatching { api.acl() }.getOrDefault(emptyList())
        // CYP-189 Invariante E: the human roster is operator-only. Fetch it ONLY when editable (operator) — a
        // non-operator never even requests GET /api/workspace/members (403 fail-closed server-side), so the
        // matrix cannot leak the roster. Fail-closed to empty on any error (never a partial/guessed list).
        val repo = workspaceRepository
        val members = if (_state.value.editable && repo != null)
            // §9.4 (PO default): omit OPERATOR-tier identities from the grantable human band — the operator's OWN
            // row (a self-grant is a no-op; they have access via tier/token) AND co-operators. Fail-safe by case.
            runCatching { repo.members() }.getOrDefault(emptyList())
                .filterNot { it.tier.equals("OPERATOR", ignoreCase = true) }
        else emptyList()
        _state.update { it.copy(channels = channels, agents = agents, entries = entries, members = members, loading = false) }
    }

    private suspend fun collectLive() {
        // CYP-289: auto-reconnect with backoff on a socket drop (CYP-73 pattern, same as Comm/AgentView). On each
        // re-subscribe the source replays its `Connected` marker → statusOf() flips connection back off DISCONNECTED
        // (banner recovers); EntryChanged is idempotent (upsert by key), ChannelsChanged replaces — no duplicates.
        liveSource.events().reconnecting(backoff).collect { event ->
            AclReducer.statusOf(event)?.let { s -> _state.update { it.copy(connection = s) } }
            when (event) {
                // The AclEvent echo is the source of truth: it reconciles the entry AND clears pending →
                // the cell becomes `enforced` (CYP-19 §5.3/§5.5, idempotent last-wins by key).
                is AclLiveEvent.EntryChanged -> _state.update {
                    val key = AclReducer.cellKey(event.entry.channelId, event.entry.agentId)
                    it.copy(
                        entries = AclReducer.upsert(it.entries, event.entry),
                        pending = it.pending - key,
                        cellNotice = it.cellNotice - key,
                    )
                }
                is AclLiveEvent.ChannelsChanged -> _state.update { it.copy(channels = event.channels) }
                is AclLiveEvent.AccessRevoked -> {
                    // CYP-289: a 1008 revoke is TERMINAL — flag it honestly and cancel the collector so
                    // `.reconnecting()` does NOT re-open /ws/comm with the revoked token (the ACL reconnect loop).
                    // Symmetric to EventTail's AccessRevoked handling.
                    _state.update { it.copy(accessRevoked = true) }
                    liveJob?.cancel()
                }
                else -> Unit
            }
        }
    }

    fun toggleRead(channelId: String, agentId: String) = toggle(channelId, agentId, AclDimension.READ)
    fun toggleWrite(channelId: String, agentId: String) = toggle(channelId, agentId, AclDimension.WRITE)

    private fun toggle(channelId: String, agentId: String, dimension: AclDimension) {
        val s = _state.value
        if (!s.editable) return
        // CYP-189 — a human subject (roster identityId in the agentId slot) is grantable but NEVER PO → no
        // hub-and-spoke lockout / self-blind guardrail applies (§7.2): apply directly. An unknown subject is
        // ignored (defensive). Agents keep the full PO-lockout path below.
        val agent = s.agents.firstOrNull { it.id == agentId }
        if (agent == null) {
            if (s.members.any { it.identityId == agentId }) applyToggle(channelId, agentId, dimension)
            return
        }
        val current = AclReducer.entryFor(s.entries, channelId, agentId)
            ?: AclEntry(channelId = channelId, agentId = agentId, canRead = false, canWrite = false)
        val newValue = when (dimension) {
            AclDimension.READ -> !current.canRead
            AclDimension.WRITE -> !current.canWrite
        }

        // §6: turning a PO-critical grant off — or the operator's OWN read off — opens a consequence
        // dialog (advisory) instead of a silent toggle. Confirming routes back through [applyToggle].
        val poLockout = AclReducer.wouldLockoutPo(channelId, agent, newValue, s.channels)
        val selfBlind = agentId == OPERATOR_ID && dimension == AclDimension.READ && !newValue
        if (poLockout || selfBlind) {
            val channelName = s.channels.firstOrNull { it.id == channelId }?.name ?: channelId
            _state.update {
                it.copy(lockoutPrompt = LockoutPrompt(channelId, agentId, dimension, channelName, selfBlind))
            }
            return
        }
        applyToggle(channelId, agentId, dimension)
    }

    /** Confirm the open PO-guardrail dialog → proceed with the toggle (server still enforces, §6/§5.4). */
    fun confirmLockout() {
        val p = _state.value.lockoutPrompt ?: return
        _state.update { it.copy(lockoutPrompt = null) }
        applyToggle(p.channelId, p.agentId, p.dimension)
    }

    fun cancelLockout() = _state.update { it.copy(lockoutPrompt = null) }

    private fun applyToggle(channelId: String, agentId: String, dimension: AclDimension) {
        val s = _state.value
        val prior = AclReducer.entryFor(s.entries, channelId, agentId)
        val base = prior ?: AclEntry(channelId, agentId, canRead = false, canWrite = false)
        val next = when (dimension) {
            AclDimension.READ -> base.copy(canRead = !base.canRead)
            AclDimension.WRITE -> base.copy(canWrite = !base.canWrite)
        }
        val key = AclReducer.cellKey(channelId, agentId)

        // Optimistic, but explicitly NOT enforced — the cell stays `pending` until the AclEvent echo.
        _state.update {
            it.copy(
                entries = AclReducer.upsert(it.entries, next),
                pending = it.pending + key,
                cellNotice = it.cellNotice - key,
                notice = null,
            )
        }

        runScope.launch {
            runCatching { api.setAcl(next) }
                .onFailure { error -> revert(key, channelId, agentId, prior, error) }
            // onSuccess: do NOT clear pending — wait for the AclEvent echo (source of truth, §5.3).
        }
        // Safeguard (§5.4): a missing echo must never hang the cell in pending.
        runScope.launch {
            delay(PENDING_TIMEOUT_MS)
            if (key in _state.value.pending) revert(key, channelId, agentId, prior, error = null)
        }
    }

    /** Revert an optimistic toggle to the hub (pre-toggle) state and surface an honest notice (§5.4). */
    private fun revert(key: String, channelId: String, agentId: String, prior: AclEntry?, error: Throwable?) {
        val (cellNoticeKey, banner) = when {
            error is AclHttpException && error.status == 409 -> "acl_po_protected" to null
            // CYP-189 §5: a grant on a ghost `channelId` → 404 (built server hardening). Honest, NON-retryable
            // banner (distinct from `acl_change_failed` "try again") — a gone channel heals by refreshing, not retry.
            error is AclHttpException && error.status == 404 -> null to "acl_channel_gone"
            error is AclHttpException && error.status == 403 -> null to "acl_operator_required"
            error is AclHttpException && error.status == 401 -> null to "acl_unauthorized"
            else -> null to "acl_change_failed"
        }
        _state.update {
            val reverted = if (prior != null) AclReducer.upsert(it.entries, prior)
            else AclReducer.remove(it.entries, channelId, agentId)
            it.copy(
                entries = reverted,
                pending = it.pending - key,
                cellNotice = if (cellNoticeKey != null) it.cellNotice + (key to cellNoticeKey) else it.cellNotice,
                notice = banner ?: it.notice,
            )
        }
    }

    // ---- Preset "restore hub-and-spoke" (non-atomic, N PUTs — CYP-19 §7) ----

    /** Show the preview diff ("N cells will change") before any write. */
    fun previewPreset() {
        val diff = AclReducer.presetDiff(_state.value.channels, _state.value.entries)
        _state.update { it.copy(preset = PresetState(PresetPhase.PREVIEW, total = diff.size)) }
    }

    fun cancelPreset() = _state.update { it.copy(preset = null) }

    /** Apply the canonical hub-and-spoke target via N idempotent PUTs, reporting honest partial failure. */
    fun applyPreset() {
        val diff = AclReducer.presetDiff(_state.value.channels, _state.value.entries)
        if (diff.isEmpty()) {
            _state.update { it.copy(preset = PresetState(PresetPhase.RESTORED)) }
            return
        }
        _state.update { it.copy(preset = PresetState(PresetPhase.APPLYING, total = diff.size)) }
        runScope.launch {
            var done = 0
            var failed = 0
            for (entry in diff) {
                val key = AclReducer.cellKey(entry.channelId, entry.agentId)
                _state.update { it.copy(pending = it.pending + key) }
                // Safeguard (§5.4), same as the single toggle: a missing AclEvent echo must never leave a
                // preset cell hanging in pending — clear it after the timeout (the row reflects hub truth).
                runScope.launch {
                    delay(PENDING_TIMEOUT_MS)
                    if (key in _state.value.pending) _state.update { it.copy(pending = it.pending - key) }
                }
                runCatching { api.setAcl(entry) }
                    .onSuccess { done++ }
                    .onFailure { failed++; _state.update { it.copy(pending = it.pending - key) } }
                _state.update { it.copy(preset = PresetState(PresetPhase.APPLYING, total = diff.size, done = done, failed = failed)) }
            }
            // Honest result: "restored" ONLY when every cell succeeded (the AclEvent echoes clear pending).
            _state.update {
                val phase = if (failed == 0) PresetPhase.RESTORED else PresetPhase.PARTIAL
                it.copy(preset = PresetState(phase, total = diff.size, done = done, failed = failed))
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    private companion object {
        /** `HubState.OPERATOR_ID` — the privileged viewer's own row (self-blind guardrail, §6c). */
        const val OPERATOR_ID = "operator"

        /** A missing `AclEvent` echo must never hang a cell in pending (§5.4). */
        const val PENDING_TIMEOUT_MS = 8_000L
    }
}
