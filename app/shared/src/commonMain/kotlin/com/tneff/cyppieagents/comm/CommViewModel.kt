package com.tneff.cyppieagents.comm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val agents: Map<String, Agent> = emptyMap(),
    val selectedChannelId: String? = null,
    val messages: List<MessageItem> = emptyList(),
    val connection: ConnectionStatus = ConnectionStatus.CONNECTING,
    val loadingHistory: Boolean = false,
    /** Operator viewer (CYP-17 PO decision): writable in MVP. Per-channel ACL is a later seam. */
    val canWrite: Boolean = true,
    val sendError: String? = null,
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
) : ViewModel() {

    private val _state = MutableStateFlow(CommUiState())
    val state: StateFlow<CommUiState> = _state.asStateFlow()

    private var optimisticSeq = 0

    init {
        viewModelScope.launch { loadChannelsAndAgents() }
        viewModelScope.launch { collectLive() }
    }

    private suspend fun loadChannelsAndAgents() {
        val channels = runCatching { repository.channels() }.getOrDefault(emptyList())
        val agents = runCatching { repository.agents() }.getOrDefault(emptyList()).associateBy { it.id }
        _state.update { it.copy(channels = channels, agents = agents) }
        // Auto-select the first readable channel for convenience.
        channels.firstOrNull()?.let { select(it.id) }
    }

    private suspend fun collectLive() {
        // Robust auto-reconnect (CYP-73): re-subscribe with backoff when the socket drops. On reconnect
        // the server replays its snapshot (Connected + ChannelsChanged + recent messages); idempotency
        // is preserved because [CommReducer.merge] dedups by `message.id` — no duplicates, no visible loss.
        liveSource.events().reconnecting(backoff).collect { event ->
            CommReducer.statusOf(event)?.let { status -> _state.update { it.copy(connection = status) } }
            if (event is CommLiveEvent.ChannelsChanged) {
                _state.update { it.copy(channels = event.channels) }
            }
            if (event is CommLiveEvent.MessageReceived && event.message.channelId == _state.value.selectedChannelId) {
                _state.update { it.copy(messages = CommReducer.merge(it.messages, event.message)) }
            }
        }
    }

    fun select(channelId: String) {
        _state.update { it.copy(selectedChannelId = channelId, messages = emptyList(), loadingHistory = true, sendError = null) }
        viewModelScope.launch {
            val history = runCatching { repository.messages(channelId) }.getOrDefault(emptyList())
            _state.update {
                if (it.selectedChannelId != channelId) it // selection changed while loading
                else it.copy(messages = CommReducer.mergeAll(emptyList(), history), loadingHistory = false)
            }
        }
    }

    fun send(text: String) {
        val body = text.trim()
        val channelId = _state.value.selectedChannelId
        if (body.isEmpty() || channelId == null || !_state.value.canWrite) return

        val tempId = "local-${optimisticSeq++}"
        val lastTs = _state.value.messages.lastOrNull()?.message?.ts ?: 0L
        val optimistic = Message(id = tempId, channelId = channelId, from = viewerId, body = body, ts = lastTs + 1)
        _state.update { it.copy(messages = CommReducer.addOptimistic(it.messages, optimistic), sendError = null) }

        viewModelScope.launch {
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
