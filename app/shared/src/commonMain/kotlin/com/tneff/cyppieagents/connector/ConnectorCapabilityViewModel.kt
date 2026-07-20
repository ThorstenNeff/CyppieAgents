package com.tneff.cyppieagents.connector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tneff.cyppieagents.agentview.AgentLifecycleSource
import com.tneff.cyppieagents.agentview.AgentLifecycleState
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.ProviderInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** CYP-742: bounded caps re-poll after a RUNNING lifecycle event. The connector opts in around agent START
 *  (`ConnectorOptIn`), which is LATER than the create event, so caps land in `GET /api/agents` shortly AFTER the
 *  RUNNING signal. The bound keeps a never-opting-in agent from being polled forever (its `null` caps are the
 *  honest "not yet reported"). Tuned modestly: a few attempts spaced a couple of seconds. */
internal const val CAPS_POLL_ATTEMPTS = 5
internal const val CAPS_POLL_INTERVAL_MS = 2_000L

/** CYP-746 — the worst-case wall-clock the bounded caps re-poll can run (attempts × interval ≈ 10 s). The badge's
 *  C→D timeout ([CAPS_CHECKING_TIMEOUT_MS]) must be ≥ this so a live-created agent's caps get the full re-poll
 *  window to land before the badge would flip to UNKNOWN — the coupling the CYP-746 tooth pins. */
internal const val CAPS_REPOLL_MAX_DURATION_MS: Long = CAPS_POLL_ATTEMPTS * CAPS_POLL_INTERVAL_MS

/**
 * Immutable UI state for the connector **capability display** (CYP-123). Holds the per-agent capability map
 * (from the read port) and which agent's detail panel is open. Fail-closed: an agent absent from
 * [capabilities] has `null` caps ⇒ "not yet reported", never faked as full (spec §0/§5).
 */
data class ConnectorCapabilityUiState(
    val capabilities: Map<String, Capabilities> = emptyMap(),
    /** Per-agent provider (CYP-137), same `GET /api/agents` snapshot as [capabilities]. Absent ⇒ not yet reported. */
    val providers: Map<String, ProviderInfo> = emptyMap(),
    /** The agent whose detail panel is open (header-badge click → [openPanel]); `null` = none open. */
    val openPanelAgentId: String? = null,
    /** CYP-280: the caps read is in flight → the fidelity badge is SUPPRESSED (not shown as `○ not-reported`)
     *  during the load window. Distinguishes "not loaded yet" (empty map on init / project switch) from
     *  "loaded but this agent is genuinely absent" — so a switch never transiently claims a full-fidelity agent
     *  is "not yet reported". Flips false once the first read settles (success OR fail-closed). */
    val loading: Boolean = true,
)

/**
 * Drives the read-only connector capability display (CYP-123, spec §2) over a [ConnectorCapabilityRepository]
 * (stub today; [ConnectorCapabilityHttpRepository] at the swap, no UI change). Fail-closed [reload]: on failure
 * the prior caps are kept and nothing is invented — a missing agent stays `null` ("not yet reported"), never
 * upgraded to full fidelity. The connector **write** (selection/opt-in) is the human's separate increment.
 *
 * **CYP-742 (Dogfood §4a):** caps are set at connector opt-in (around agent **START**), LATER than the create
 * event, and the once-at-init [reload] never re-reads — so a live-created-then-started agent hung permanently on
 * "not yet reported". [lifecycleSource]'s RUNNING events (≈ the opt-in moment) now trigger a **bounded caps
 * re-poll** for that agent: caps land shortly after opt-in, the bound tolerates the opt-in↔observe race, and an
 * agent that never opts in stays honestly `null` (a created-but-NOT-started agent emits no RUNNING event → the
 * honest "not yet reported" persists, correct until it starts). `null` [lifecycleSource] → once-at-init only.
 */
class ConnectorCapabilityViewModel(
    private val repository: ConnectorCapabilityRepository,
    /** CYP-742: the `/ws/lifecycle` source (same one the agent windows use); `null` → no re-poll (once-at-init). */
    private val lifecycleSource: AgentLifecycleSource? = null,
    scope: CoroutineScope? = null,
    /** CYP-742 bounded-poll tuning (injectable for tests). */
    private val capsPollAttempts: Int = CAPS_POLL_ATTEMPTS,
    private val capsPollIntervalMs: Long = CAPS_POLL_INTERVAL_MS,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow(ConnectorCapabilityUiState())
    val state: StateFlow<ConnectorCapabilityUiState> = _state.asStateFlow()

    init {
        runScope.launch { reload() }
        // CYP-742: a RUNNING lifecycle event ≈ the connector opt-in moment → caps become available in the next
        // read(s). Re-poll (bounded) for THAT agent until its caps land. collectLatest → a newer event supersedes
        // an in-flight re-poll (no pile-up). STOPPED/ERROR/UNKNOWN don't gate caps → ignored.
        lifecycleSource?.let { src ->
            runScope.launch {
                src.events()
                    .filter { it.state == AgentLifecycleState.RUNNING }
                    .collectLatest { ev -> repollCapsUntilReported(ev.agentId) }
            }
        }
    }

    private suspend fun reload() {
        runCatching { repository.read() }
            .onSuccess { snap -> _state.update { it.copy(capabilities = snap.capabilities, providers = snap.providers, loading = false) } }
            .onFailure { e ->
                if (e is CancellationException) throw e
                // Keep prior caps; fail-closed (no invented caps/provider). CYP-280: the read settled (failed) →
                // stop suppressing the badge, so a genuinely-unreported agent honestly shows `○`.
                _state.update { it.copy(loading = false) }
            }
    }

    /**
     * CYP-742 — bounded caps re-poll for [agentId] after its RUNNING signal: read, stop as soon as its caps are
     * present, else wait [capsPollIntervalMs] and retry, up to [capsPollAttempts]. Fail-closed like [reload] (a
     * failed read keeps prior caps, never invents). Bounded → an agent whose connector never reports caps stays
     * honestly `null` ("not yet reported") instead of being polled forever.
     */
    private suspend fun repollCapsUntilReported(agentId: String) {
        repeat(capsPollAttempts) {
            runCatching { repository.read() }
                .onSuccess { snap -> _state.update { it.copy(capabilities = snap.capabilities, providers = snap.providers, loading = false) } }
                .onFailure { e -> if (e is CancellationException) throw e }
            if (_state.value.capabilities[agentId] != null) return
            delay(capsPollIntervalMs)
        }
    }

    /** The caps for [agentId], or `null` when not reported (fail-closed — never a faked "full" default). */
    fun capabilitiesFor(agentId: String): Capabilities? = _state.value.capabilities[agentId]

    /** The provider for [agentId], or `null` when not reported (fail-closed — never an invented provider). */
    fun providerFor(agentId: String): ProviderInfo? = _state.value.providers[agentId]

    fun openPanel(agentId: String) = _state.update { it.copy(openPanelAgentId = agentId) }
    fun closePanel() = _state.update { it.copy(openPanelAgentId = null) }
}
