package com.tneff.cyppieagents.comm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.net.Backoff
import com.tneff.cyppieagents.net.reconnecting
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Immutable UI state for the comm panel. */
data class CommUiState(
    val channels: List<Channel> = emptyList(),
    /** CYP-279: channels-list load in flight — gate the "no channels" empty-state on `!loadingChannels` so it
     *  never FLASHES during the initial / project-switch load window (the CYP-270/276 flash class). */
    val loadingChannels: Boolean = true,
    /** CYP-288: the channel-list load FAILED (distinct from a settled-empty list — error beats empty). */
    val channelsError: Boolean = false,
    val agents: Map<String, Agent> = emptyMap(),
    val selectedChannelId: String? = null,
    val messages: List<MessageItem> = emptyList(),
    val connection: ConnectionStatus = ConnectionStatus.CONNECTING,
    /**
     * CYP-819 (D2): a terminal **1008 (VIOLATED_POLICY) auth-revoke** on `/ws/comm` — its OWN state, NOT folded into
     * [connection]=DISCONNECTED (a revoked socket is terminal + distinct, not a transient offline drop; the
     * safe-but-silent class). Drives the ERROR-red banner (supersedes the amber offline banner) + the composer
     * hard-lock (independent of [canWrite]). Mirrors `AclUiState.accessRevoked` (1:1).
     */
    val accessRevoked: Boolean = false,
    val loadingHistory: Boolean = false,
    /** CYP-288: the selected channel's history load FAILED (distinct from an empty channel — error beats empty). */
    val historyError: Boolean = false,
    /**
     * CYP-273: the set of channel ids the resolved caller may WRITE to right now — server-authoritative
     * (`GET /api/channels/writable`, `writable ⊆ readable`), refreshed live (debounced) on every ACL change
     * so a revoke/grant takes effect without a restart (S7). `null` = not yet known (loading / fetch error /
     * endpoint not yet wired) → **fail-closed**. The server 403 stays the real enforcement; this set only
     * drives the composer's honest enable/disable, never the security boundary.
     */
    val writable: Set<String>? = null,
    val sendError: String? = null,
    /**
     * B1 activity badge (CYP-55, WINDOW-BADGES §2-B): count of messages from **other** participants
     * received while the comm window was **not** focused, since it was last focused. Session-local,
     * client-only, never persisted — an honest "new since you last looked", **not** "unread of record".
     * Reset to 0 (and suppressed) while the comm window is focused ([markCommFocused]); 0 → no badge.
     */
    val unreadCount: Int = 0,
) {
    /**
     * CYP-273 — the DERIVED write permission for the **selected** channel, driving the composer. Tri-state,
     * fail-closed:
     * - `null`  → unknown: no selection, or [writable] not yet known (loading / error / pre-wire) → composer
     *             disabled, but **no** false "no permission" claim (honesty — an unknown is not a denial,
     *             the CYP-288 class).
     * - `false` → the channel is readable but NOT in [writable] → known read-only → the proactive read-only hint.
     * - `true`  → the selected channel is in [writable] → editable composer.
     * Only an explicit membership yields `true`, so an un-resolved writable set can never open the composer.
     */
    val canWrite: Boolean?
        get() {
            val w = writable ?: return null
            val sel = selectedChannelId ?: return null
            return sel in w
        }
}

/**
 * Drives the comm panel: loads channels/agents/history over [CommRepository], folds the live
 * [CommLiveSource] stream into the selected channel's timeline via [CommReducer], and sends with
 * optimistic "sending" entries that resolve on the server's confirmed [Message.id].
 *
 * The live source is the stub today; swapping in the `/ws/comm` adapter (CYP-18) touches only the
 * source passed here — no change to this VM, the reducer, or the UI.
 */
class CommViewModel(
    private val repository: CommApi,
    private val liveSource: CommLiveSource,
    private val viewerId: String,
    /**
     * CYP-273: the narrow seam for the caller's OWN writable channel set (`GET /api/channels/writable` →
     * `List<String>`, server-authoritative). `null` = not wired yet (Backend's endpoint not merged) → the VM
     * falls back to the interim MVP posture "every readable channel is writable" (CYP-17), so there is NO
     * regression today. Swapping in the real [WritableChannelsApi] once the endpoint lands is a one-line
     * shell change; the binding below (fetch → fail-closed → live debounced refresh) is already final.
     */
    private val writableChannels: WritableChannelsApi? = null,
    /** Reconnect backoff for the live stream (CYP-73); injectable so tests can drive fast reconnects. */
    private val backoff: Backoff = Backoff(),
    /** CYP-288: injectable so tests run the loads synchronously (Unconfined) → deterministic waitForIdle. */
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow(CommUiState())
    val state: StateFlow<CommUiState> = _state.asStateFlow()

    private var optimisticSeq = 0

    /**
     * B1 (CYP-55): whether the comm window currently has focus. While focused, incoming activity is
     * "seen" → the unread counter is suppressed/reset, so leaving the window only ever surfaces
     * genuinely new traffic. A [MutableStateFlow] (not a plain field) so the collector's read is a
     * safe volatile read across the focus-callback / collect dispatchers.
     */
    private val commFocused = MutableStateFlow(false)

    /** CYP-291: the live-collect job, cancelled on a terminal AccessRevoked so the reconnect loop stops. */
    private var liveJob: Job? = null

    /** CYP-273: the pending debounced writable re-fetch (S7 live). Cancel-and-reschedule = last-write-wins. */
    private var writableRefreshJob: Job? = null

    init {
        // Merge (CYP-291 liveJob-capture + A2 runScope injection): both MUST survive — the terminal-revoke cancel
        // needs liveJob, the deterministic tests need runScope. AccessRevoked→liveJob.cancel() is in collectLive().
        runScope.launch { loadChannelsAndAgents() }
        liveJob = runScope.launch { collectLive() }
    }

    private suspend fun loadChannelsAndAgents() {
        val channelsResult = runCatching { repository.channels() }
        channelsResult.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        val channels = channelsResult.getOrDefault(emptyList())
        val agents = runCatching { repository.agents() }.getOrDefault(emptyList()).associateBy { it.id }
        // CYP-288: carry a channel-list load failure instead of swallowing it to empty (failure ≠ "no channels").
        _state.update { it.copy(channels = channels, agents = agents, loadingChannels = false, channelsError = channelsResult.isFailure) }
        // CYP-273: resolve the writable set for the freshly-loaded channels BEFORE auto-select, so the composer
        // reflects the real (or interim) write right the instant a channel is selected — never a fail-closed flash.
        refreshWritable()
        // Auto-select the first readable channel for convenience.
        channels.firstOrNull()?.let { select(it.id) }
    }

    /**
     * CYP-273: (re)load the caller's OWN writable channel set through the [writableChannels] seam. **Fail-closed**
     * (PO CYP-273): an error / not-yet-known result leaves `writable = null` → the composer disables (never
     * optimistically open). When the seam is not wired yet (`null`), the interim MVP posture applies: every
     * readable channel is writable (CYP-17) — no regression until Backend's endpoint lands.
     */
    private suspend fun refreshWritable() {
        val api = writableChannels
        if (api == null) {
            _state.update { it.copy(writable = it.channels.map { c -> c.id }.toSet()) }
            return
        }
        val result = runCatching { api.writableChannels() }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        _state.update { it.copy(writable = result.getOrNull()?.toSet()) } // fail-closed: error → null → disabled
    }

    /**
     * CYP-273/S7: coalesce a burst of ACL changes into a single debounced writable re-fetch, so a revoked or
     * granted write takes effect live without a restart. Cancel-and-reschedule → only the last change in a
     * window triggers the fetch.
     */
    private fun scheduleWritableRefresh() {
        writableRefreshJob?.cancel()
        writableRefreshJob = runScope.launch {
            delay(WRITABLE_REFRESH_DEBOUNCE_MS)
            refreshWritable()
        }
    }

    /** CYP-288: retry a failed channel-list load (driven by the LoadErrorRetry surface). */
    fun reloadChannels() {
        _state.update { it.copy(loadingChannels = true, channelsError = false) }
        runScope.launch { loadChannelsAndAgents() }
    }

    private suspend fun collectLive() {
        // Robust auto-reconnect (CYP-73): re-subscribe with backoff when the socket drops. On reconnect
        // the server replays its snapshot (Connected + ChannelsChanged + recent messages); idempotency
        // is preserved because [CommReducer.merge] dedups by `message.id` — no duplicates, no visible loss.
        liveSource.events().reconnecting(backoff).collect { event ->
            CommReducer.statusOf(event)?.let { status -> _state.update { it.copy(connection = status) } }
            if (event is CommLiveEvent.AccessRevoked) {
                // CYP-819 (D2): a 1008 revoke is a DISTINCT terminal state, not a transient offline — set the own
                // flag so the ERROR banner supersedes the amber offline banner and the composer hard-locks
                // (independent of writability). NOT folded into DISCONNECTED (the safe-but-silent class).
                _state.update { it.copy(accessRevoked = true) }
                // CYP-291: a 1008 revoke is TERMINAL — cancel the collector so `.reconnecting()` does NOT re-open
                // /ws/comm with the revoked token (the reconnect loop). The connection already reads DISCONNECTED.
                liveJob?.cancel()
            }
            if (event is CommLiveEvent.AclChanged) {
                // CYP-273/S7: an ACL row (incl. the caller's own (channel,self) grant) changed → re-fetch the
                // writable set (debounced) so a revoked/granted write disables/enables the composer live,
                // without a restart. The server 403 stays the enforcement point; this is honesty/comfort only.
                scheduleWritableRefresh()
            }
            if (event is CommLiveEvent.ChannelsChanged) {
                _state.update { it.copy(channels = event.channels) }
                // The readable set changed → the writable subset may have too (and the interim posture derives
                // from the channel list) → re-resolve, debounced.
                scheduleWritableRefresh()
            }
            if (event is CommLiveEvent.MessageReceived) {
                // B1 (CYP-55): count comm-wide activity from OTHERS while unfocused — across all
                // readable channels, not just the selected one (the live source is already ACL-filtered).
                // Own (optimistic/echoed) messages never raise the badge; suppressed while focused.
                if (!commFocused.value && event.message.from != viewerId) {
                    _state.update { it.copy(unreadCount = it.unreadCount + 1) }
                }
                if (event.message.channelId == _state.value.selectedChannelId) {
                    _state.update { it.copy(messages = CommReducer.merge(it.messages, event.message)) }
                }
            }
        }
    }

    /**
     * B1 (CYP-55): the comm window gained or lost focus. On gaining focus the activity badge clears
     * (the hint is "done") and stays suppressed until focus is lost again — so the count only ever
     * reflects traffic that arrived while you were elsewhere.
     */
    fun markCommFocused(focused: Boolean) {
        commFocused.value = focused
        if (focused) _state.update { it.copy(unreadCount = 0) }
    }

    fun select(channelId: String) {
        // CYP-273: `canWrite` is DERIVED from (writable, selectedChannelId) — just changing the selection
        // re-resolves the composer's write right for the new channel (per-channel, not a global flag).
        _state.update { it.copy(selectedChannelId = channelId, messages = emptyList(), loadingHistory = true, sendError = null, historyError = false) }
        runScope.launch {
            val result = runCatching { repository.messages(channelId) }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            // CYP-288: carry a history load failure instead of swallowing it to empty (failure ≠ "empty channel").
            _state.update {
                if (it.selectedChannelId != channelId) it // selection changed while loading
                else it.copy(
                    messages = CommReducer.mergeAll(emptyList(), result.getOrDefault(emptyList())),
                    loadingHistory = false,
                    historyError = result.isFailure,
                )
            }
        }
    }

    /** Clears the channel selection (CYP-156 single-pane "back": return to the channel list). */
    fun clearSelection() {
        // CYP-273: no selected channel → `canWrite` derives to `null` (no write target) → fail-closed, no claim.
        _state.update { it.copy(selectedChannelId = null, messages = emptyList(), loadingHistory = false, sendError = null) }
    }

    fun send(text: String) {
        val body = text.trim()
        val channelId = _state.value.selectedChannelId
        // CYP-273: fail-closed — only an explicit `true` (known write right) permits a send. `null` (unknown)
        // and `false` (read-only) both block, so an un-resolved writability can never leak an optimistic send.
        if (body.isEmpty() || channelId == null || _state.value.canWrite != true) return

        val tempId = "local-${optimisticSeq++}"
        val lastTs = _state.value.messages.lastOrNull()?.message?.ts ?: 0L
        val optimistic = Message(id = tempId, channelId = channelId, from = viewerId, body = body, ts = lastTs + 1)
        _state.update { it.copy(messages = CommReducer.addOptimistic(it.messages, optimistic), sendError = null) }

        runScope.launch {
            runCatching { repository.send(channelId, body) }
                .onSuccess { confirmed ->
                    _state.update {
                        if (it.selectedChannelId != channelId) it
                        else it.copy(messages = CommReducer.confirm(it.messages, tempId, confirmed))
                    }
                }
                .onFailure { error ->
                    // Drop the optimistic entry and surface an honest denial/error (CYP-17 §5).
                    _state.update {
                        it.copy(
                            messages = it.messages.filterNot { m -> m.message.id == tempId },
                            sendError = if (error is CommHttpException && error.status == 403) "comm_send_denied" else "comm_send_failed",
                        )
                    }
                }
        }
    }

    /** Optional helper: the [Message]'s meta kind, for the kind badge. */
    fun kindOf(item: MessageItem): MessageMeta? = item.message.meta

    companion object {
        /** CYP-273: debounce window collapsing ACL-change bursts into a single writable re-fetch (S7 live). */
        const val WRITABLE_REFRESH_DEBOUNCE_MS = 250L
    }
}
