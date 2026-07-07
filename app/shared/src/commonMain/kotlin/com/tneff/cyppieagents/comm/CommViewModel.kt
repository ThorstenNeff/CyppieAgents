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
    val loadingHistory: Boolean = false,
    /** CYP-288: the selected channel's history load FAILED (distinct from an empty channel — error beats empty). */
    val historyError: Boolean = false,
    /** Operator viewer (CYP-17 PO decision): writable in MVP. Per-channel ACL is a later seam. */
    val canWrite: Boolean = true,
    val sendError: String? = null,
    /**
     * B1 activity badge (CYP-55, WINDOW-BADGES §2-B): count of messages from **other** participants
     * received while the comm window was **not** focused, since it was last focused. Session-local,
     * client-only, never persisted — an honest "new since you last looked", **not** "unread of record".
     * Reset to 0 (and suppressed) while the comm window is focused ([markCommFocused]); 0 → no badge.
     */
    val unreadCount: Int = 0,
)

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
        // Auto-select the first readable channel for convenience.
        channels.firstOrNull()?.let { select(it.id) }
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
                // CYP-291: a 1008 revoke is TERMINAL — cancel the collector so `.reconnecting()` does NOT re-open
                // /ws/comm with the revoked token (the reconnect loop). The connection already reads DISCONNECTED.
                liveJob?.cancel()
            }
            if (event is CommLiveEvent.ChannelsChanged) {
                _state.update { it.copy(channels = event.channels) }
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
        _state.update { it.copy(selectedChannelId = null, messages = emptyList(), loadingHistory = false, sendError = null) }
    }

    fun send(text: String) {
        val body = text.trim()
        val channelId = _state.value.selectedChannelId
        if (body.isEmpty() || channelId == null || !_state.value.canWrite) return

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
}
