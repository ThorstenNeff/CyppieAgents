package com.tneff.cyppieagents.agentview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.connector.ConnectorCapabilityBadge
import com.tneff.cyppieagents.connector.ConnectorProviderChip
import com.tneff.cyppieagents.eventlog.severityColor
import com.tneff.cyppieagents.eventlog.severityContainer
import com.tneff.cyppieagents.model.AgentTerminalControlEvent
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.PO_AGENT_ID
import com.tneff.cyppieagents.model.ProviderInfo
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.model.TerminalControlState
import com.tneff.cyppieagents.testing.testTagA11y
import com.tneff.cyppieagents.ui.ThinVerticalScrollbar
import com.tneff.cyppieagents.window.COMPOSER_COMPACT_INPUT_THRESHOLD
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_agent_status
import kmpcyppieagents.app.shared.generated.resources.a11y_assistant_streaming
import kmpcyppieagents.app.shared.generated.resources.a11y_notice
import kmpcyppieagents.app.shared.generated.resources.a11y_result_error
import kmpcyppieagents.app.shared.generated.resources.a11y_result_success
import kmpcyppieagents.app.shared.generated.resources.a11y_tool_error
import kmpcyppieagents.app.shared.generated.resources.a11y_tool_ok
import kmpcyppieagents.app.shared.generated.resources.a11y_tool_running
import kmpcyppieagents.app.shared.generated.resources.a11y_transcript_system
import kmpcyppieagents.app.shared.generated.resources.a11y_transcript_time
import kmpcyppieagents.app.shared.generated.resources.a11y_agent_turn_undelivered
import kmpcyppieagents.app.shared.generated.resources.a11y_user_turn
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import kmpcyppieagents.app.shared.generated.resources.agent_composer_readonly_hint
import kmpcyppieagents.app.shared.generated.resources.agent_composer_unknown_hint
import kmpcyppieagents.app.shared.generated.resources.agent_turn_undelivered
import kmpcyppieagents.app.shared.generated.resources.transcript_system_label
import kmpcyppieagents.app.shared.generated.resources.agent_ctl_err_agent_not_found
import kmpcyppieagents.app.shared.generated.resources.agent_ctl_err_already_running
import kmpcyppieagents.app.shared.generated.resources.agent_ctl_err_generic
import kmpcyppieagents.app.shared.generated.resources.agent_ctl_err_operator_required
import kmpcyppieagents.app.shared.generated.resources.agent_ctl_err_spawn_failed
import kmpcyppieagents.app.shared.generated.resources.agent_ctl_err_unreachable
import kmpcyppieagents.app.shared.generated.resources.a11y_agent_ctl_unconfigured
import kmpcyppieagents.app.shared.generated.resources.agent_ctl_restart
import kmpcyppieagents.app.shared.generated.resources.agent_ctl_start
import kmpcyppieagents.app.shared.generated.resources.agent_ctl_unconfigured
import kmpcyppieagents.app.shared.generated.resources.agent_ctl_stop
import com.tneff.cyppieagents.comm.ConnectionStatus
import kmpcyppieagents.app.shared.generated.resources.agent_edit_effect_hint
import kmpcyppieagents.app.shared.generated.resources.agent_persona_pending_badge
import kmpcyppieagents.app.shared.generated.resources.agent_reconnecting
import kmpcyppieagents.app.shared.generated.resources.agent_status_error
import kmpcyppieagents.app.shared.generated.resources.agent_status_running
import kmpcyppieagents.app.shared.generated.resources.agent_status_starting
import kmpcyppieagents.app.shared.generated.resources.agent_status_restarting
import kmpcyppieagents.app.shared.generated.resources.agent_status_stopped
import kmpcyppieagents.app.shared.generated.resources.agent_status_unknown
import kmpcyppieagents.app.shared.generated.resources.terminal_mode_orchestration
import kmpcyppieagents.app.shared.generated.resources.terminal_mode_terminal
import kmpcyppieagents.app.shared.generated.resources.terminal_session_note
import kmpcyppieagents.app.shared.generated.resources.terminal_gated_pending
import kmpcyppieagents.app.shared.generated.resources.terminal_mode_switching
import kmpcyppieagents.app.shared.generated.resources.terminal_idle_defer
import kmpcyppieagents.app.shared.generated.resources.a11y_terminal_idle_defer
import kmpcyppieagents.app.shared.generated.resources.terminal_mode_swap_failed
import kmpcyppieagents.app.shared.generated.resources.terminal_handoff_banner
import kmpcyppieagents.app.shared.generated.resources.a11y_terminal_handoff
import kmpcyppieagents.app.shared.generated.resources.terminal_context_lost
import kmpcyppieagents.app.shared.generated.resources.a11y_terminal_context_lost
import kmpcyppieagents.app.shared.generated.resources.transcript_context_lost
import kmpcyppieagents.app.shared.generated.resources.a11y_transcript_context_lost
import kmpcyppieagents.app.shared.generated.resources.transcript_tool_run_collapsed
import kmpcyppieagents.app.shared.generated.resources.transcript_tool_run_errors
import kmpcyppieagents.app.shared.generated.resources.a11y_transcript_tool_run_collapsed
import kmpcyppieagents.app.shared.generated.resources.a11y_transcript_tool_run_collapsed_with_errors
import kmpcyppieagents.app.shared.generated.resources.a11y_transcript_tool_run_open_with_errors
import kmpcyppieagents.app.shared.generated.resources.a11y_transcript_tool_run_expand
import kmpcyppieagents.app.shared.generated.resources.a11y_transcript_tool_run_collapse
import kmpcyppieagents.app.shared.generated.resources.a11y_transcript_tool_run_state_collapsed
import kmpcyppieagents.app.shared.generated.resources.a11y_transcript_tool_run_state_expanded
import kmpcyppieagents.app.shared.generated.resources.a11y_terminal_mode
import kmpcyppieagents.app.shared.generated.resources.workspace_operator_only
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-350 — the width at which the agent header can render its three lifecycle controls with WORDS on them,
 * measured on the rendered composition at the shipped state (status + fidelity badge present):
 *
 * ```
 * status 74 + badge 199 + controls (59 + 58 + 76) + 4 gaps of 8 = 498, plus 16 dp of header padding = 514
 * ```
 *
 * Below it the `Row` cannot lay the labels out side by side. What it did instead was NOT "shrink the text": it
 * gave the first control 15 dp and the next two **zero** (CYP-369), then stacked the surviving label's letters
 * into a 116 dp column, which is where the header's 164 dp height at 320 dp came from.
 *
 * 520 rather than 514: the two extra dp per control keep the labels off the padding, and a floor derived to the
 * last dp of one font would move with the next one. `AgentHeaderControlsGuardTest` measures the real composition
 * on both sides of this line, so it cannot drift silently.
 */
const val HEADER_LABELLED_CONTROLS_MIN_WIDTH: Float = 520f

/**
 * The agent window: a scrolling transcript of [AgentEvent]s over a "message to the agent" composer, with a
 * CYP-333 content-view toggle to a real Terminal (a fresh PTY over `/ws/terminal`, CYP-332/334).
 *
 * [agentId] parameterizes the test tags per the v0.2 Test-Contract (`agent.<agentId>.stream` etc.).
 *
 * **CYP-381 hand-off (delivered):** the toggle now drives the real backend hand-off — [AgentViewModel.requestMode]
 * issues the non-optimistic `POST /api/agents/{id}/mode` against the CYP-355 motor, and the view flips only on the
 * server confirm. In TERMINAL mode the motor holds an interactive `claude --resume` session (the agent's real, same
 * session) that this window attaches to as a viewer; the mediated stream-json reader steps aside, so the **hub-blind**
 * banner (INTERACTIVE) and the CONTEXT_LOST landmark ARE surfaced here (§6/§7b), mirrored from the read-only CYP-354
 * [control] state. The `bash -l` worktree shell (CYP-348) remains as the interim fallback for when no motor session
 * is live (see [terminalGatedNote]).
 */
@Composable
fun AgentWindow(
    agentId: String,
    viewModel: AgentViewModel,
    modifier: Modifier = Modifier,
    /** CYP-123 connector fidelity for this agent (`null` = not yet reported → fail-closed badge). */
    capabilities: Capabilities? = null,
    /** CYP-280: caps read in flight → the fidelity badge is suppressed (no transient `○` on a switch). */
    capabilitiesLoading: Boolean = false,
    /** CYP-137 provider for this agent (`null` = not yet reported → chip absent, fail-closed). */
    provider: ProviderInfo? = null,
    /** Opens the capability detail panel (CYP-123); no-op default keeps existing call sites/tests intact. */
    onCapabilityBadgeClick: () -> Unit = {},
    /**
     * CYP-333: renders the Terminal into the content rectangle when the view is [AgentContentMode.TERMINAL].
     * `null` (default) = no terminal wired (pure-render / test call sites) → the Terminal segment is disabled.
     * The shell supplies a lambda that binds a fresh [com.tneff.cyppieagents.terminal.WsTerminalSession] to the
     * Desktop `TerminalView`. Passed as a slot (not a session factory) so tests can exercise the swap without a
     * headless `SwingPanel`.
     */
    terminalContent: (@Composable (agentId: String, modifier: Modifier) -> Unit)? = null,
    /**
     * CYP-333: when `true` AND no [terminalContent] is wired, the Terminal segment is disabled with an honest
     * "available once the terminal backend lands" note (for an operator). Normally the terminal is **live**: in
     * TERMINAL mode the CYP-355 motor holds the agent's interactive `claude --resume` session (with a `bash -l`
     * worktree shell as the interim fallback when no motor session is live). This gated state is the exceptional
     * case — a non-Desktop target or the [WORKTREE_SHELL_LIVE_ENABLED] kill-switch turned off.
     * `terminalContent` present = live; `terminalGatedNote` = honestly gated.
     */
    terminalGatedNote: Boolean = false,
    /**
     * CYP-381 §6/§7b: this agent's read-only CYP-354 terminal-control event (the same `/ws/terminal-state` truth the
     * titlebar marker mirrors), threaded so the window frame can render the **hub-blind** banner (INTERACTIVE) and
     * the **context-lost** banner (CONTEXT_LOST). `null`/MEDIATED → no banner (fail-closed, absent == MEDIATED). With
     * the live motor these states are reached and the banners render; a test/demo injecting a stub feed that reports
     * only MEDIATED sees no banner — honest either way.
     */
    control: AgentTerminalControlEvent? = null,
    /**
     * CYP-629 §6.3c: the hub is unconfigured (no API key and/or repo not `CLONED_OK`), so a spawn cannot succeed.
     * `false` (default) = byte-identical today — this is a SEAM: it is bound to the first-run config status by the
     * (deferred, opt-in-off) App wiring, together with [com.tneff.cyppieagents.firstrun.FirstRunGate]. When `true`
     * the per-agent Start control is pre-emptively GATED with an honest, visible reason (`agent_ctl_unconfigured`),
     * BEFORE the click — not a silent fail after it. The server stays the fail-closed backstop (a race that slips
     * through returns the same reason via the existing `agent_ctl_err_*` surface, Backend-owned).
     */
    hubUnconfigured: Boolean = false,
    /**
     * CYP-819 (A2): the session-wide **1008 auth-revoke** is active (fanned in from the four status feeds) → this
     * window DEMOTES its live indicators to their unknown/absent form (the honesty core): the run-state dot falls to
     * UNKNOWN, and the reconnecting `↻` chip is SUPPRESSED — a terminal revoke is "disconnected/entzogen" (carried by
     * the covering banner), never "reconnecting" (which implies recovery). Busy/token/control demote at the shell
     * (the WindowHost lambdas). Absent by default (`false` = byte-identical today).
     */
    statusRevoked: Boolean = false,
) {
    val transcript by viewModel.transcript.collectAsState()
    val lifecycle by viewModel.lifecycleState.collectAsState()
    val startPending by viewModel.startPending.collectAsState()
    val restartPending by viewModel.restartPending.collectAsState()
    // CYP-239: persona (CLAUDE.md) changed but not yet active — the restart reminder that lost its home when
    // CYP-237 closed the settings dialog on save. Rendered as a persistent header badge (see AgentHeader).
    val personaPendingRestart by viewModel.personaPendingRestart.collectAsState()
    val lifecycleError by viewModel.lifecycleError.collectAsState()
    val connection by viewModel.connection.collectAsState()
    val contentMode by viewModel.contentMode.collectAsState()
    val modeSwitching by viewModel.modeSwitching.collectAsState()
    val agentBusy by viewModel.busy.collectAsState()
    // CYP-738: the agent composer's send-writability tri-state (dormant UNGATED until the AgentWritableApi seam is wired).
    val composerWritability by viewModel.composerWritability.collectAsState()
    // CYP-381 §7.1: the DURABLE CONTEXT_LOST landmark. The live control-state is momentary (CONTEXT_LOST → MEDIATED
    // on the next real turn), but "the agent does not remember the scrollback above the loss" is permanent for that
    // buffer. So latch the loss instant here and NEVER clear it — anchored to the loss ts, it survives recovery, so
    // the transcript discontinuity + receded history stay put while the titlebar ∅ / banner (live) clear. Keeps the
    // ONE momentary→durable split honest (cf. HandoffBanners, which are the live half).
    val contextLostAt = remember(agentId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(control) {
        // `since` is bound to a LOCAL val: it is a `:core` property (a different module), which Kotlin will not
        // smart-cast — so compare the local, not `control.since`.
        val since = control?.since
        if (control?.state == TerminalControlState.CONTEXT_LOST &&
            since != null && since > (contextLostAt.value ?: Long.MIN_VALUE)
        ) {
            contextLostAt.value = since
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        lifecycleError?.let { code -> LifecycleErrorRow(agentId, code) }
        // CYP-381 §6/§7b: persistent WARN frame strips, driven by the CYP-354 control-state (fail-closed — absent
        // unless the backend reports INTERACTIVE / CONTEXT_LOST; the stub never does → honestly absent).
        HandoffBanners(agentId, control)
        AgentHeader(
            agentId = agentId,
            state = lifecycle,
            startPending = startPending,
            restartPending = restartPending,
            connection = connection,
            statusRevoked = statusRevoked,
            canControl = viewModel.canControl,
            hubUnconfigured = hubUnconfigured,
            personaPendingRestart = personaPendingRestart,
            onStart = viewModel::start,
            onStop = viewModel::stop,
            onRestart = viewModel::restart,
            capabilities = capabilities,
            capabilitiesLoading = capabilitiesLoading,
            provider = provider,
            onCapabilityBadgeClick = onCapabilityBadgeClick,
        )
        // CYP-629 §6.3c: the honest, VISIBLE reason the Start control is gated — adjacent to the header controls
        // (mirrors [LifecycleErrorRow]'s placement), present BEFORE the click. GATED, not ERROR: nothing failed, a
        // prerequisite is missing. Absent when configured (fail-closed by absence). The a11y reason additionally
        // rides ON the Start button as its `stateDescription` (AgentLifecycleControls).
        if (hubUnconfigured) StartUnconfiguredGateRow(agentId)
        // CYP-333: the mode toggle lives in a FRAME row above the content rectangle. Z-order (04 §5): the Desktop
        // terminal is a `SwingPanel` that renders OVER the Compose layer, so chrome must frame it, never overlay it.
        ModeToggleRow(
            agentId = agentId,
            mode = contentMode,
            canControl = viewModel.canControl,
            terminalAvailable = terminalContent != null,
            terminalGatedNote = terminalGatedNote,
            // CYP-381: non-optimistic hand-off command (flip only after the server confirm; IDLE-gated take-over).
            onModeChange = viewModel::requestMode,
            switching = modeSwitching,
            busy = agentBusy,
        )
        // The content rectangle (04 §5): transcript OR terminal, weight 1f. The terminal occupies ONLY this
        // rectangle — no Compose chrome is z-stacked over it.
        Box(modifier = Modifier.weight(1f).fillMaxWidth().testTag(AgentViewTags.content(agentId))) {
            when (contentMode) {
                AgentContentMode.ORCHESTRATION ->
                    AgentTranscript(agentId = agentId, events = transcript, contextLostAt = contextLostAt.value, foldToolRuns = agentId == PO_AGENT_ID, modifier = Modifier.fillMaxSize())
                AgentContentMode.TERMINAL ->
                    // Defensive: the toggle disables the Terminal segment when no terminal is wired, so this
                    // branch is normally unreachable without [terminalContent]; fall back to the transcript.
                    terminalContent?.invoke(agentId, Modifier.fillMaxSize())
                        ?: AgentTranscript(agentId = agentId, events = transcript, contextLostAt = contextLostAt.value, foldToolRuns = agentId == PO_AGENT_ID, modifier = Modifier.fillMaxSize())
            }
        }
        // The mediated composer belongs to the Orchestrierung view only — the terminal has its own input. It is
        // NOT a "mediation is off" claim (the session runs); it just isn't shown while you look at the terminal.
        if (contentMode == AgentContentMode.ORCHESTRATION) {
            MessageComposer(
                agentId = agentId,
                onSend = viewModel::onSend,
                history = viewModel::inputHistorySnapshot,
                writability = composerWritability,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * CYP-333 §4.1 — the `[ Orchestrierung | Terminal ]` content-view toggle. **Operator-gated + fail-closed**
 * (CYP-317 "no fake switch"): a non-operator sees the live mode read-only plus the reused
 * `workspace_operator_only` hint, never a switch that lies. The Terminal segment also disables when no terminal
 * is wired ([terminalAvailable]). The Orchestrierung segment stays reachable for an operator (returning to it
 * claims nothing). Intent, not backend state — this slice has no hand-off to confirm.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeToggleRow(
    agentId: String,
    mode: AgentContentMode,
    canControl: Boolean,
    terminalAvailable: Boolean,
    terminalGatedNote: Boolean,
    onModeChange: (AgentContentMode) -> Unit,
    // CYP-381: a hand-off command in flight ([switching]) while the server holds it through its bounded-wait; [busy]
    // distinguishes the §4 IDLE-defer hint ("wartet bis Turn fertig", a take-over issued mid-turn) from a plain
    // "switching…". A reject renders on the shared lifecycleError row (§3.4), not here.
    switching: Boolean = false,
    busy: Boolean = false,
) {
    val orchLabel = stringResource(Res.string.terminal_mode_orchestration)
    // CYP-381 (§8 rename): the second view is now the agent's REAL, interactive session (claude --resume via the
    // hand-off motor) — so the segment reads "Terminal". The honesty flag is resolved: the real session exists.
    val termLabel = stringResource(Res.string.terminal_mode_terminal)
    val currentLabel = if (mode == AgentContentMode.TERMINAL) termLabel else orchLabel
    val viewDescription = stringResource(Res.string.a11y_terminal_mode, currentLabel)
    val idleDeferA11y = stringResource(Res.string.a11y_terminal_idle_defer)
    // CYP-836: the in-flight switch is aria-busy parity — a `stateDescription`, NOT a live pulse (§2/§4: switching = no
    // announce). Focus-readable state; the toggle's Assertive fires only on the CONFIRMED flip (above), not here.
    val switchingLabel = stringResource(Res.string.terminal_mode_switching)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .testTag(AgentViewTags.modeToggle(agentId))
                // Announces the current view (spec §8 `a11y_terminal_mode`); a plain description does not merge
                // the child segments, so each SegmentedButton still speaks its own label + selected state.
                // CYP-836: the CONFIRMED (non-optimistic, server-flipped) view change is the RESULT of an operator-
                // submitted switch he is waiting for → Assertive (A11Y-ANNOUNCEMENTS §1). `viewDescription` changes
                // ONLY when `mode` flips (confirmed) — an in-flight `switching` does NOT change `mode`, so it never
                // pulses here (that stays a `stateDescription`, below).
                .semantics { contentDescription = viewDescription; liveRegion = LiveRegionMode.Assertive },
        ) {
            SegmentedButton(
                selected = mode == AgentContentMode.ORCHESTRATION,
                onClick = { onModeChange(AgentContentMode.ORCHESTRATION) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                enabled = canControl,
                modifier = Modifier.testTag(AgentViewTags.modeToggleOrchestration(agentId)),
            ) { Text(orchLabel, maxLines = 1) }
            SegmentedButton(
                selected = mode == AgentContentMode.TERMINAL,
                onClick = { onModeChange(AgentContentMode.TERMINAL) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                // Fail-closed: never openable by a non-operator (CYP-317), nor when no terminal is wired.
                enabled = canControl && terminalAvailable,
                modifier = Modifier.testTag(AgentViewTags.modeToggleTerminal(agentId)),
            ) { Text(termLabel, maxLines = 1) }
        }
        when {
            // CYP-381 §4 IDLE-defer: a take-over issued mid-turn — the server holds the POST until the turn settles
            // (bounded-wait); the honest waiting hint, distinct from a plain switch (no silent hijack). Own node.
            switching && busy -> Text(
                text = stringResource(Res.string.terminal_idle_defer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .testTag(AgentViewTags.modeDeferHint(agentId))
                    .semantics { contentDescription = idleDeferA11y },
            )
            // CYP-381 (provisional): the command is in flight — the view has NOT flipped yet (non-optimistic).
            switching -> Text(
                text = switchingLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // CYP-836: stateDescription (aria-busy parity), NO liveRegion — the in-flight switch must not pulse.
                modifier = Modifier
                    .testTag(AgentViewTags.modeSwitching(agentId))
                    .semantics { stateDescription = switchingLabel },
            )
            // Non-operator: honest read-only disclosure (reuse `workspace_operator_only`, CYP-317 no-fake-switch).
            !canControl -> Text(
                text = stringResource(Res.string.workspace_operator_only),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(AgentViewTags.modeToggleGateHint(agentId)),
            )
            // CYP-381 (§8 rename): Terminal view active → honest descriptor: this IS the agent's real, interactive
            // session (claude --resume, same session); typing goes to the agent, the hub does not mediate here.
            mode == AgentContentMode.TERMINAL && terminalAvailable -> Text(
                text = stringResource(Res.string.terminal_session_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(AgentViewTags.modeToggleTerminalNote(agentId)),
            )
            // Operator, but no live interactive backend (non-Desktop target / kill-switch off) → say WHY the
            // Terminal segment is off, rather than a silently-disabled control. Neutral tone (deferral, not error).
            terminalGatedNote && !terminalAvailable -> Text(
                text = stringResource(Res.string.terminal_gated_pending),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(AgentViewTags.modeToggleTerminalGated(agentId)),
            )
        }
    }
}

/** Honest surfacing of a lifecycle-control failure (CYP-73) — the server's reason, not a generic blur. CYP-381 §3.4:
 *  a mode-swap reject reuses THIS row (`mode_swap_failed` → `terminal_mode_swap_failed`), not a separate node. */
@Composable
private fun LifecycleErrorRow(agentId: String, code: String) {
    val text = when (code) {
        "already_running" -> stringResource(Res.string.agent_ctl_err_already_running)
        "spawn_failed" -> stringResource(Res.string.agent_ctl_err_spawn_failed)
        "operator_required" -> stringResource(Res.string.agent_ctl_err_operator_required)
        // CYP-598-A: two causes that previously fell to the generic `else` ("Action failed") — a documented server
        // code (`agent_not_found`, per the AgentLifecycleHttpException KDoc) and a client-side transport failure
        // (`unreachable`, emitted by the VM when the request never reached the server). Now each reads honestly.
        "agent_not_found" -> stringResource(Res.string.agent_ctl_err_agent_not_found)
        "unreachable" -> stringResource(Res.string.agent_ctl_err_unreachable)
        "mode_swap_failed" -> stringResource(Res.string.terminal_mode_swap_failed) // CYP-381 §3.4
        else -> stringResource(Res.string.agent_ctl_err_generic)
    }
    Text(
        text = text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag(AgentViewTags.lifecycleError(agentId))
            // CYP-836: ERROR (and the CYP-381 §3.4 mode_swap_failed that reuses this row) is the RESULT of an
            // operator-submitted action / an unsolicited-critical failure → Assertive, its OWN node (§3c; parity web
            // `lifecycle-error` role=alert). Never the ambient status node flipped loud — this carries the reason.
            .semantics { liveRegion = LiveRegionMode.Assertive },
    )
}

/**
 * CYP-629 §6.3c — the honest GATED reason for the disabled Start control while the hub is unconfigured. GATED tone
 * (neutral `onSurfaceVariant`, NOT `error`): nothing failed, the prerequisite (API key + repo) is missing — the same
 * doctrine as [modeToggleGateHint]. Rendered only while gated; absent when configured (fail-closed by absence).
 */
@Composable
private fun StartUnconfiguredGateRow(agentId: String) {
    Text(
        text = stringResource(Res.string.agent_ctl_unconfigured),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag(AgentViewTags.startBtnGateHint(agentId)),
    )
}

/**
 * CYP-381 §6/§7b — the persistent WARN frame strips driven by the CYP-354 control-state (never keystrokes/PTV
 * content, state/identity/time only). **INTERACTIVE** → the **hub-blind** banner ("der Hub vermittelt nicht" +
 * holder + since): the honest "you are driving the real session" consequence, complementary to the titlebar
 * marker. **CONTEXT_LOST** → the context-lost banner (returned without prior history). Both WARN-amber
 * ([severityColor]) — never green. **Fail-closed:** absent for MEDIATED / transient / `null` — the stub reports
 * none of these, so with the interim shell no banner shows (honest). The CONTEXT_LOST transcript chrome
 * (dimmed history + discontinuity line, spec §7b) is deferred to the motor bundle (PO scope-ruling).
 */
@Composable
private fun HandoffBanners(agentId: String, control: AgentTerminalControlEvent?) {
    when (control?.state) {
        TerminalControlState.INTERACTIVE -> {
            val holder = control.heldBy?.let { "@$it" } ?: "?"
            val since = control.since?.let { formatLocalHhMm(it) } ?: "—"
            FrameBanner(
                text = stringResource(Res.string.terminal_handoff_banner, holder, since),
                a11y = stringResource(Res.string.a11y_terminal_handoff, control.heldBy ?: "?", since),
                tag = AgentViewTags.handoffBanner(agentId),
            )
        }
        TerminalControlState.CONTEXT_LOST -> FrameBanner(
            text = stringResource(Res.string.terminal_context_lost),
            a11y = stringResource(Res.string.a11y_terminal_context_lost),
            tag = AgentViewTags.contextLostBanner(agentId),
        )
        else -> Unit // MEDIATED / HANDING_* / null → no banner (fail-closed, absent == MEDIATED)
    }
}

/** A persistent WARN-amber frame strip (§6/§7b). WARN carried by `severityColor(WARN)` (never green); the a11y
 *  description carries the full meaning (WCAG 1.4.1 — tone is reinforcement). */
@Composable
private fun FrameBanner(text: String, a11y: String, tag: String) {
    Text(
        text = text,
        color = severityColor(Severity.WARN),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag(tag)
            // CYP-836: the terminal-control change (INTERACTIVE hub-blind / CONTEXT_LOST) is unsolicited + critical —
            // the operator may be typing elsewhere and MUST hear it → Assertive (A11Y-ANNOUNCEMENTS §1). This is the
            // once-per-appearance banner announce the transcript landmark defers to. (Web renders it role=status
            // /polite — flagged to PO as a cross-surface delta; Compose is louder here by the doctrine + PO ruling.)
            .semantics { contentDescription = a11y; liveRegion = LiveRegionMode.Assertive },
    )
}

/**
 * Window header (CYP-73): the **non-gated** lifecycle status indicator on the start, the
 * **operator-gated** Start/Stop/Restart controls on the end. Controls are always present so the gate
 * is observable, but `enabled` only with an operator token ([canControl]) and a sensible state —
 * fail-closed; the server enforces the operator gate too (403).
 */
@Composable
private fun AgentHeader(
    agentId: String,
    state: AgentLifecycleState,
    canControl: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
    /** CYP-629 §6.3c: the hub is unconfigured → the Start control is GATED with a visible honest reason. */
    hubUnconfigured: Boolean = false,
    /** CYP-262: a Start request is in flight → the status shows the transient "Startet…" (client-only). */
    startPending: Boolean = false,
    /** CYP-330: a Restart request is in flight → the status shows the transient "Neustart…" (client-only). */
    restartPending: Boolean = false,
    /** CYP-239: a persona (CLAUDE.md) change was saved but the running agent still has the OLD file → a persistent
     *  "wirkt erst beim Neustart" badge, until the agent's next RUNNING event. Absent by default (fail-closed). */
    personaPendingRestart: Boolean = false,
    connection: ConnectionStatus = ConnectionStatus.LIVE,
    /** CYP-819 (A2): session-wide 1008 revoke → demote the dot to UNKNOWN and SUPPRESS the reconnecting `↻` chip
     *  (a terminal revoke is not a recoverable reconnect). Absent by default (byte-identical). */
    statusRevoked: Boolean = false,
    capabilities: Capabilities? = null,
    capabilitiesLoading: Boolean = false,
    provider: ProviderInfo? = null,
    onCapabilityBadgeClick: () -> Unit = {},
) {
    // CYP-819 (A2): on a session-wide 1008 revoke the feeds are dead → the last state is NOT current. Demote the dot
    // to UNKNOWN by treating the connection as DISCONNECTED (drives gatedLifecycleState → RING/"unbekannt" + the
    // capability badge → NOT_STARTED); the reconnecting `↻` chip is suppressed separately (revoke ≠ reconnect).
    val effectiveConnection = if (statusRevoked) ConnectionStatus.DISCONNECTED else connection
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // CYP-350: below this width the labelled controls cannot be laid out at their intrinsic size, so they are
        // rendered as glyph buttons instead. Measured, not chosen: at 480 dp the three `TextButton`s still need
        // more room than the row has and it wraps (header 104 dp at 480, 164 dp at 320); at 520 dp everything sits
        // on one 56 dp line. `AgentHeaderControlsGuardTest` re-derives the switch from the rendered composition
        // rather than trusting this number.
        val compact = maxWidth < HEADER_LABELLED_CONTROLS_MIN_WIDTH.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(AgentViewTags.header(agentId))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // CYP-369 — **the distribution rule.** A `Row` measures its UNWEIGHTED children first, in order, each
            // against what the previous ones left over. The controls used to come last, behind a
            // `Spacer(Modifier.weight(1f))`, so at 320 dp the identity cluster (status 74 + fidelity badge 199)
            // consumed the row and Stop and Restart were measured with `maxWidth = 0`: `w = 0 dp`,
            // `displayed = false`. The operator could neither stop nor restart an agent in a tiled window, and
            // nothing said so — a control that is absent and a control that is 0 dp wide look the same from
            // outside.
            //
            // The weight belongs on the part that may YIELD, not on a spacer between them. The identity cluster is
            // now the weighted child: measured LAST, with whatever the controls did not need. Its members are
            // markers, and a marker may shrink; a control may not.
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusIndicator(agentId, state, startPending, restartPending, effectiveConnection)
                // CYP-204: reconnecting indicator — present ONLY while the per-agent WS is not LIVE (the adapter is
                // auto-reconnecting from the seq cursor; on reconnect the server replays the history gapless). Its OWN
                // axis, next to but distinct from the lifecycle status (process state ≠ socket state).
                // CYP-819 (A2): SUPPRESSED on a session-wide revoke — a terminal 1008 is not a recoverable reconnect,
                // so `↻` would falsely imply recovery; the covering banner carries the honest "entzogen" instead.
                if (!statusRevoked) ReconnectingChip(agentId, effectiveConnection)
                // Provider axis (CYP-137) — the subordinate "(Claude)" qualifier next to the identity/status, its OWN
                // marker (≠ fidelity, ≠ lifecycle). Present only when known (fail-closed by absence); neutral, no hue.
                ConnectorProviderChip(provider = provider, agentId = agentId)
                // Fidelity axis (CYP-123) — its own marker next to the lifecycle status, NOT mixed into it. Present
                // only when degraded / not-yet-reported (fail-closed by absence); opens the capability panel.
                //
                // CYP-350: `compact` drops the badge's WORD, never its meaning. The glyph stays, and the badge's
                // `contentDescription` is unchanged, so a screen reader reads the same sentence at every width.
                // This is not a shortened disclosure — the disclosure itself lives in the panel the badge opens.
                ConnectorCapabilityBadge(
                    caps = capabilities,
                    agentId = agentId,
                    // CYP-746: the SAME connection-gated effective state the lifecycle dot shows (see StatusIndicator)
                    // — so the badge's D-vs-E stays coherent with the dot. A dropped socket gates RUNNING→UNKNOWN →
                    // the badge falls to NOT_STARTED (no `○`) exactly as the dot falls to the RING, never a stale `○`.
                    lifecycle = gatedLifecycleState(state, startPending || restartPending, effectiveConnection),
                    onClick = onCapabilityBadgeClick,
                    loading = capabilitiesLoading,
                    compact = compact,
                )
                // CYP-239: the persona-restart-pending badge — its own marker (≠ lifecycle, ≠ fidelity), present only
                // while a saved CLAUDE.md change awaits the agent's restart (fail-closed by absence). Compact-aware
                // like the fidelity badge: the glyph + a11y sentence stay, the word drops at narrow widths.
                PersonaRestartBadge(agentId = agentId, pending = personaPendingRestart, compact = compact)
            }
            AgentLifecycleControls(
                agentId = agentId,
                state = state,
                canControl = canControl,
                hubUnconfigured = hubUnconfigured,
                compact = compact,
                onStart = onStart,
                onStop = onStop,
                onRestart = onRestart,
            )
        }
    }
}

/**
 * CYP-350/369 — the three lifecycle controls: labelled when the header can afford it, glyph-only when it cannot.
 * **Unweighted on purpose** (see the distribution rule in [AgentHeader]): they are measured before the identity
 * cluster and therefore always get their intrinsic width.
 *
 * The glyph form is not a smaller label, it is a different control: an `IconButton` is 48 dp square, comfortably
 * past the 24 dp WCAG 2.5.8 target, where the squeezed `TextButton` was 15 dp wide and 116 dp tall — a column of
 * stacked letters. `maxLines = 1` would have stopped the stacking and left the width at zero: **wrapping was the
 * symptom, not the cause.** The accessible name stays the string the label would have shown, so a screen reader
 * loses nothing — only the eye does, and only where there was no room for it anyway.
 *
 * There is no material-icons artifact in `:app:shared`, so the glyph is plain text carrying an a11y name — the
 * same pattern as `WindowManager`'s window controls and `AgentSettingsPanel`.
 */
@Composable
private fun AgentLifecycleControls(
    agentId: String,
    state: AgentLifecycleState,
    canControl: Boolean,
    hubUnconfigured: Boolean,
    compact: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    // CYP-629 §6.3c: the honest a11y reason ON the Start control while gated — `stateDescription` (a11y-spec §1),
    // so a screen reader reads "Start, unavailable — hub not configured" (the WHY, before the click). Only Start.
    val startUnconfiguredA11y = stringResource(Res.string.a11y_agent_ctl_unconfigured)
    data class Control(
        val label: String,
        val glyph: String,
        val tag: String,
        val enabled: Boolean,
        val stateDescription: String?,
        val onClick: () -> Unit,
    )
    val controls = listOf(
        Control(
            label = stringResource(Res.string.agent_ctl_start),
            glyph = "▶",
            tag = AgentViewTags.startBtn(agentId),
            // CYP-629 §6.3c: a THIRD barrier beside the operator gate and the running-state gate. A spawn needs a
            // configured hub (API key + repo) — pre-emptively disabled, honestly, so the operator never sees a
            // silent fail / eternal spinner. (The other two barriers already exist; this one is additive.)
            enabled = canControl && state != AgentLifecycleState.RUNNING && !hubUnconfigured,
            stateDescription = if (hubUnconfigured) startUnconfiguredA11y else null,
            onClick = onStart,
        ),
        Control(
            label = stringResource(Res.string.agent_ctl_stop),
            glyph = "■",
            tag = AgentViewTags.stopBtn(agentId),
            enabled = canControl && state == AgentLifecycleState.RUNNING,
            stateDescription = null,
            onClick = onStop,
        ),
        Control(
            label = stringResource(Res.string.agent_ctl_restart),
            glyph = "↻",
            tag = AgentViewTags.restartBtn(agentId),
            // CYP-330: enabled for any operator — Restart must not be hard-ineffective in UNKNOWN/STOPPED (a
            // stopped/unknown agent is exactly when you want to bring it back). The server stays authoritative and
            // surfaces an honest reason if the transition is invalid; the "Neustart…" transient acknowledges the click.
            enabled = canControl,
            stateDescription = null,
            onClick = onRestart,
        ),
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        controls.forEach { control ->
            if (compact) {
                IconButton(
                    onClick = control.onClick,
                    enabled = control.enabled,
                    modifier = Modifier.testTag(control.tag).semantics {
                        contentDescription = control.label
                        control.stateDescription?.let { stateDescription = it }
                    },
                ) { Text(control.glyph, maxLines = 1) }
            } else {
                TextButton(
                    onClick = control.onClick,
                    enabled = control.enabled,
                    modifier = Modifier.testTag(control.tag).semantics {
                        control.stateDescription?.let { stateDescription = it }
                    },
                ) { Text(control.label, maxLines = 1) }
            }
        }
    }
}

/**
 * CYP-204: the reconnecting chip — shown ONLY when the per-agent WS is not [ConnectionStatus.LIVE]. Text (not
 * colour-only) carries the meaning (WCAG 1.4.1); absent on a healthy socket so a LIVE window adds no chrome.
 */
@Composable
private fun ReconnectingChip(agentId: String, connection: ConnectionStatus) {
    if (connection == ConnectionStatus.LIVE) return
    val label = stringResource(Res.string.agent_reconnecting)
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        // CYP-300 (a0): reconnecting is transient in-progress → neutral onSurfaceVariant, never `tertiary`
        // (E1 → green reads as "connected", the inverse). The label text carries the meaning.
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .testTag(AgentViewTags.reconnecting(agentId))
            .semantics { contentDescription = label },
    )
}

/**
 * CYP-239 — the persona-restart-pending badge. **Fail-closed by absence:** rendered ONLY while [pending] (a saved
 * CLAUDE.md persona change the running agent has not picked up yet). This is the home the CYP-237 close-on-save took
 * from the in-dialog `agent_edit_effect_hint` — persistent at the agent header until the agent's next RUNNING event
 * (see [AgentViewModel.personaPendingRestart]). EFFECT_DEFERRED tone (a *deferred effect*, not an error). Compact-aware
 * like the fidelity badge (CYP-350): the glyph + the full a11y sentence stay at every width, only the word drops. The
 * accessible name is the reused full hint (`agent_edit_effect_hint`), so a screen reader gets the whole reason.
 */
@Composable
private fun PersonaRestartBadge(agentId: String, pending: Boolean, compact: Boolean) {
    if (!pending) return
    val a11y = stringResource(Res.string.agent_edit_effect_hint)
    Row(
        modifier = Modifier
            .testTag(AgentViewTags.personaRestartBadge(agentId))
            .semantics { contentDescription = a11y },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // CYP-300 (a0): EFFECT_DEFERRED = onSecondaryContainer (secondary/blue Attention), parity with the fidelity
        // badge's degraded marker and TonedHint — a marked deferral, never an error red. `↻` = restart (plain text,
        // no emoji — CYP-54, reliable on Desktop-JVM).
        Text("↻", color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.bodySmall)
        if (!compact) {
            Text(
                text = stringResource(Res.string.agent_persona_pending_badge),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
    }
}

/** CYP-396: the status dot's drawn form. UNKNOWN is a RING (a different axis than STOPPED), all else a filled disc. */
internal enum class StatusDotShape { FILL, RING }

/** CYP-396: the status dot's colour ROLE (resolved to a `colorScheme` colour by the composable). NEUTRAL = onSurfaceVariant (pending). */
internal enum class StatusDotRole { PRIMARY, OUTLINE, ERROR, NEUTRAL }

/**
 * CYP-396 — form + colour-role for the status dot, as a PURE decision so both are unit-testable without a pixel
 * compare (pattern: Cyp392ScrollbarStyleTest pins the style as a value). UNKNOWN = RING/OUTLINE — never a pale
 * disc that collapses onto STOPPED's look; "unknown" is a different axis, not weaker certainty. `pending`
 * (Start…/Neustart…) wins for every state.
 */
internal fun statusDotSpec(state: AgentLifecycleState, pending: Boolean): Pair<StatusDotShape, StatusDotRole> {
    if (pending) return StatusDotShape.FILL to StatusDotRole.NEUTRAL
    return when (state) {
        AgentLifecycleState.RUNNING -> StatusDotShape.FILL to StatusDotRole.PRIMARY
        AgentLifecycleState.STOPPED -> StatusDotShape.FILL to StatusDotRole.OUTLINE
        AgentLifecycleState.ERROR -> StatusDotShape.FILL to StatusDotRole.ERROR
        AgentLifecycleState.UNKNOWN -> StatusDotShape.RING to StatusDotRole.OUTLINE // ← the fix
    }
}

/**
 * CYP-573 — the connection-gate: the lifecycle dot/label may only assert a RESOLVED run-state ([state]) while its
 * live feed is actually [ConnectionStatus.LIVE]. The lifecycle source holds the last-known state across a WS drop
 * (a reconnect just re-streams the snapshot and upserts — [AgentLifecycleLiveSource]), so across a real server/hub
 * restart the dot would keep showing a **stale RUNNING for an already-stopped agent** through the reconnect gap —
 * a state it can no longer prove. Fail closed: while the socket is not LIVE, collapse the resolved state to
 * [AgentLifecycleState.UNKNOWN] (RING, "unbekannt") — honest absence, never a phantom claim.
 *
 * Gated on the SAME [session][com.tneff.cyppieagents.agentview.AgentViewModel.connection] signal the
 * [ReconnectingChip] uses, so the two move in lockstep: chip visible ⟺ dot UNKNOWN (no independent flap surface).
 * [pending] (Startet…/Neustart…) is a client-local transient INTENT, not a resolved-state claim — it is surfaced by
 * the label branch and always resolves on the next lifecycle event, so it is passed through untouched here.
 */
internal fun gatedLifecycleState(
    state: AgentLifecycleState,
    pending: Boolean,
    connection: ConnectionStatus,
): AgentLifecycleState {
    if (pending) return state
    return if (connection == ConnectionStatus.LIVE) state else AgentLifecycleState.UNKNOWN
}

@Composable
private fun StatusIndicator(
    agentId: String,
    state: AgentLifecycleState,
    startPending: Boolean = false,
    restartPending: Boolean = false,
    connection: ConnectionStatus = ConnectionStatus.LIVE,
) {
    // CYP-262/330 Teil 1: while a Start/Restart request is in flight (client-only, before the server's event),
    // show the honest transient "Startet…"/"Neustart…" instead of the resolved state — NEVER a resolved label
    // before the server confirms it (§9-1). Both flags always resolve on the next lifecycle event, so neither can
    // stick. Same node/tag (0 new tag) and the in-progress NEUTRAL tone (CYP-300 a0: onSurfaceVariant). The
    // "Neustart…" flash is the visible acknowledgement of a RUNNING→RUNNING restart (the swallowed-click fix).
    val pending = startPending || restartPending
    // CYP-573: gate the RESOLVED state on the live-feed connection so BOTH the label and the dot fail closed to
    // "unbekannt" on a WS drop (gating only the dot would leave the text still asserting "Läuft" — WCAG 1.4.1: the
    // text carries the meaning). Pending passes through (it drives the Startet…/Neustart… branch below, unchanged).
    val effectiveState = gatedLifecycleState(state, pending, connection)
    val label = when {
        startPending -> stringResource(Res.string.agent_status_starting)
        restartPending -> stringResource(Res.string.agent_status_restarting)
        else -> when (effectiveState) {
            AgentLifecycleState.RUNNING -> stringResource(Res.string.agent_status_running)
            AgentLifecycleState.STOPPED -> stringResource(Res.string.agent_status_stopped)
            AgentLifecycleState.ERROR -> stringResource(Res.string.agent_status_error)
            AgentLifecycleState.UNKNOWN -> stringResource(Res.string.agent_status_unknown)
        }
    }
    // CYP-396: form + colour-role are a pure decision (see statusDotSpec) so both are testable without a pixel
    // compare. UNKNOWN becomes a RING (a different AXIS from STOPPED), never a pale disc — role `outline` (3.55/
    // 3.63 ≥ 3:1 on its own, WCAG 1.4.11) instead of the near-invisible `outlineVariant` (1.41/1.52).
    val (dotShape, dotRole) = statusDotSpec(effectiveState, pending)
    val dotColor = when (dotRole) {
        StatusDotRole.PRIMARY -> MaterialTheme.colorScheme.primary
        StatusDotRole.OUTLINE -> MaterialTheme.colorScheme.outline
        StatusDotRole.ERROR -> MaterialTheme.colorScheme.error
        StatusDotRole.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant // a0: neutral, distinct from RUNNING=primary
    }
    val dotModifier = when (dotShape) {
        StatusDotShape.FILL -> Modifier.size(8.dp).clip(CircleShape).background(dotColor)
        StatusDotShape.RING -> Modifier.size(8.dp).border(2.dp, dotColor, CircleShape) // open centre, still 8 dp
    }
    val description = stringResource(Res.string.a11y_agent_status, label)
    Row(
        modifier = Modifier
            .testTag(AgentViewTags.status(agentId))
            // CYP-836: the ONE announce carrier for the ambient lifecycle state (§3d de-dup — busy stays visual-only).
            // Polite: an ambient status of a window the operator is looking at, not an alarm (A11Y-ANNOUNCEMENTS §1;
            // parity with web `LifecycleHeader` role=status). Announces the status WORD on change (WCAG 1.4.1). The
            // ERROR *reason* is a SEPARATE Assertive node (LifecycleErrorRow, §3c) — this node is never flipped loud.
            .semantics { contentDescription = description; liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Colour is never the sole signal (WCAG 1.4.1): the text label carries the meaning; the dot
        // only reinforces it. CYP-396: UNKNOWN is a RING, every other state a filled disc (see statusDotSpec).
        Box(dotModifier)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AgentTranscript(
    agentId: String,
    events: List<AgentEvent>,
    modifier: Modifier = Modifier,
    /** CYP-381 §7.1: the DURABLE CONTEXT_LOST loss instant (epoch-ms), or null for no landmark. Events before it are
     *  the agent's forgotten history (receded, role-demoted + gutter rail); a WARN discontinuity band marks the
     *  boundary. Anchored to the loss ts, NOT the live control-state — so the boundary persists after recovery. */
    contextLostAt: Long? = null,
    /** CYP-790: fold long, clean, completed ToolCall/Result runs into a collapsible summary (op-po anti-flood).
     *  Default false → byte-identical to today; the op-po (PO agent) window opts in at the call-site (§14). */
    foldToolRuns: Boolean = false,
) {
    val listState = rememberLazyListState()
    // CYP-393 — pin the transcript to the live end, but RELEASE when the user scrolls up to read back and RESUME
    // when they return to the bottom. Three deliberate choices, from the UIUX note:
    //  1. Follow the tail's GROWTH, not `events.size`: a streaming AssistantText delta grows ONE item in-place
    //     (TranscriptFolding), so the list size is constant while streaming — the follow keys on a tail SIGNATURE
    //     (size + last-event identity), which changes on every delta.
    //  2. "At bottom" is a ~48dp TOLERANCE (`transcriptAtBottom`), not exact `!canScrollForward` — streaming and
    //     the scroll landing sit a few px off exact and would flicker the pin.
    //  3. Release only on a USER scroll, never on our own follow or the agent's typing (CYP-351: never infer
    //     intent from our own action). The follow uses the INSTANT `scrollToItem` (never `animateScrollToItem`),
    //     so `isScrollInProgress` is a clean "the USER is scrolling" signal here — our scrolls never set it, and
    //     content growth (instant follow) never trips it. It covers drag, wheel AND keyboard uniformly.
    val tolerancePx = with(LocalDensity.current) { TRANSCRIPT_BOTTOM_TOLERANCE_DP.dp.roundToPx() }
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            transcriptAtBottom(
                totalItems = info.totalItemsCount,
                lastVisibleIndex = last?.index,
                lastVisibleItemBottom = (last?.offset ?: 0) + (last?.size ?: 0),
                viewportEndOffset = info.viewportEndOffset,
                tolerancePx = tolerancePx,
            )
        }
    }
    var pinned by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { atBottom to listState.isScrollInProgress }.collect { (bottom, scrolling) ->
            when {
                scrolling && !bottom -> pinned = false // §4 eager release: the user is scrolling away from the end
                bottom -> pinned = true                // §4 resume: settled back within the bottom tolerance
            }
        }
    }
    // §7.1: the boundary = the first event at/after the loss (events are time-ascending); if no fresh row exists yet,
    // the band tails the buffer. ts-positioned so it survives buffer trims and generalizes past a single loss.
    val boundary = contextLostAt?.let { ts ->
        events.indexOfFirst { it.tsMs >= ts }.let { if (it < 0) events.size else it }
    }
    // CYP-790: the render model — Single rows, or (op-po only, §14) folded ToolCall/Result runs. A run breaks at
    // the §15 [boundary] so a fold can never span the CONTEXT_LOST landmark. foldToolRuns=false → 1:1 Singles,
    // byte-identical to today. Recomputed only when its inputs change (not per-recompose).
    val renderItems = remember(events, foldToolRuns, boundary) { transcriptItems(events, foldToolRuns, boundary) }
    LaunchedEffect(events.size, events.lastOrNull(), pinned) {
        if (pinned && renderItems.isNotEmpty()) {
            // `scrollToItem` forces a synchronous remeasure; yield past the current measure/layout pass first, or
            // an effect that fires during the initial composition throws "performMeasureAndLayout during measure".
            // CYP-790: follow to the RENDERED last index (a fold shortens the list vs. raw `events`) — but keyed
            // on the raw `events` signature (size + last identity), which still changes on every append/delta.
            withFrameNanos {}
            listState.scrollToItem(renderItems.lastIndex)
        }
    }
    // CYP-790 §12: the operator's fold-override per run, keyed by the run's stable identity (its FIRST event.id).
    // Held here (survives recompose/new events), NOT re-derived from the list — an opened run never re-collapses
    // while the PO keeps talking. Absent key ⇒ the §10 default (clean+completed ⇒ collapsed; error ⇒ open).
    val foldOverrides = remember { mutableStateMapOf<String, Boolean>() }
    // CYP-392: the transcript scrolls; overlay a vertical scrollbar on the right edge (Desktop + Web — the seam
    // is a no-op on Android/iOS). Only shown when the content actually overflows the viewport (`canScroll*`).
    Box(modifier = modifier) {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTagA11y(AgentViewTags.stream(agentId)),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        itemsIndexed(renderItems, key = { _, item -> item.key }) { _, item ->
            val receded = boundary != null && item.startIndex < boundary // §7.1: forgotten history, above the landmark
            val content: @Composable () -> Unit = {
                when (item) {
                    // A single event renders exactly as before (byte-identical) — the whole non-op-po path is this.
                    is TranscriptItem.Single -> TranscriptEventRow(agentId, item.startIndex, item.event, receded)
                    // CYP-790: a folded ToolCall/Result run — collapsible summary + (on expand) the original rows.
                    is TranscriptItem.Run -> ToolRunGroup(agentId, item, receded, foldOverrides)
                }
            }
            // §7.1: the durable discontinuity band sits ATOP the first fresh (post-loss) item. §15: a run never
            // spans the boundary, so the landmark always falls on an item START (never mid-fold).
            if (boundary != null && item.startIndex == boundary) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TranscriptDiscontinuityRow(agentId)
                    content()
                }
            } else {
                content()
            }
        }
        // §7.1: loss just happened, no fresh row yet → the band is the tail landmark (history all above it).
        if (boundary != null && boundary >= events.size) {
            item(key = "ctx-lost-divider-$agentId") { TranscriptDiscontinuityRow(agentId) }
        }
      }
      // CYP-392: the vertical scrollbar, overlaid on the transcript's right edge — only when the content
      // overflows (canScroll*), so a short transcript shows none. No-op on Android/iOS (see ThinVerticalScrollbar).
      if (listState.canScrollForward || listState.canScrollBackward) {
          ThinVerticalScrollbar(
              listState = listState,
              modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().testTag(AgentViewTags.scrollbar(agentId)),
          )
      }
    }
}

/**
 * CYP-790 — the per-event row dispatch, extracted so a [TranscriptItem.Single] AND a folded run's expanded
 * children render through ONE path (a child keeps its original `agent.<id>.event.<index>…` tag, §7). The body is
 * byte-identical to the pre-CYP-790 inline `when` in [AgentTranscript].
 */
@Composable
private fun TranscriptEventRow(agentId: String, index: Int, event: AgentEvent, receded: Boolean) {
    // CYP-335: the `HH:mm` gutter wraps EVERY line kind — one place, so no row type can be forgotten.
    TranscriptRow(agentId = agentId, index = index, tsMs = event.tsMs, receded = receded) {
        when (event) {
            is AgentEvent.AssistantText -> AssistantTextRow(
                event,
                Modifier.testTagA11y(AgentViewTags.event(agentId, index, EventKind.ASSISTANT_TEXT)),
                receded = receded,
            )
            is AgentEvent.ToolCall -> ToolCallRow(
                event,
                Modifier.testTagA11y(AgentViewTags.event(agentId, index, EventKind.TOOL_CALL)),
            )
            is AgentEvent.Result -> ResultRow(
                event,
                Modifier.testTagA11y(AgentViewTags.event(agentId, index, EventKind.TOOL_RESULT)),
            )
            // Notice has no kind in the v0.4 vocabulary → index tag only (kind qualifier is optional).
            is AgentEvent.Notice -> NoticeRow(
                event,
                Modifier.testTag(AgentViewTags.event(agentId, index)),
            )
            is AgentEvent.UserTurn -> UserTurnRow(
                event,
                agentId,
                index,
                Modifier.testTagA11y(AgentViewTags.event(agentId, index, EventKind.USER_TURN)),
            )
            is AgentEvent.IncomingSystem -> IncomingSystemRow(
                event,
                Modifier.testTagA11y(AgentViewTags.event(agentId, index, EventKind.INCOMING_SYSTEM)),
            )
        }
    }
}

/**
 * CYP-790 — a folded ToolCall/Result run: a collapsible summary header (always the true step count, §3 Zahn 1;
 * plus a fail-loud error marker, Zahn 2) over the original rows, which reappear UNCHANGED on expand (nothing is
 * destroyed). Collapsed default = the run's §10 default; the operator's toggle overrides it for THIS run only,
 * persisted by the run's stable id in [foldOverrides] (§12) so it never re-collapses as the PO keeps talking.
 */
@Composable
private fun ToolRunGroup(
    agentId: String,
    run: TranscriptItem.Run,
    receded: Boolean,
    foldOverrides: MutableMap<String, Boolean>,
) {
    val collapsed = foldOverrides[run.key] ?: run.defaultCollapsed
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ToolRunHeader(
            agentId = agentId,
            run = run,
            collapsed = collapsed,
            receded = receded,
            onToggle = { foldOverrides[run.key] = !collapsed },
        )
        if (!collapsed) {
            run.events.forEachIndexed { offset, ev ->
                TranscriptEventRow(agentId, run.startIndex + offset, ev, receded)
            }
        }
    }
}

/**
 * CYP-790 — the run summary/toggle header. Full-width toggle (Role.Button, keyboard Enter/Space via [clickable]);
 * the chevron is a decorative second signal ([clearAndSetSemantics]) — meaning rides `contentDescription` (count
 * + error clause, §6 keys; never colour/glyph alone) + the toggle `onClickLabel`. Sits in the CYP-335 gutter at
 * the run's FIRST event ts (§15). Zahn 1: the count always shows; Zahn 2: an error run adds `✗ %d Fehler` in the
 * error role. Receded (pre-CONTEXT_LOST, §15) demotes the header text to `onSurfaceVariant`.
 */
@Composable
private fun ToolRunHeader(
    agentId: String,
    run: TranscriptItem.Run,
    collapsed: Boolean,
    receded: Boolean,
    onToggle: () -> Unit,
) {
    val steps = run.stepCount
    val toggleLabel = stringResource(
        if (collapsed) Res.string.a11y_transcript_tool_run_expand else Res.string.a11y_transcript_tool_run_collapse,
    )
    // CYP-796: `stateDescription` must be the STATE ("eingeklappt"/"ausgeklappt"), not the ACTION (`toggleLabel` =
    // "Schritte anzeigen/einklappen"). The action rides `onClickLabel` on the clickable; the state rides here.
    val stateDesc = stringResource(
        if (collapsed) Res.string.a11y_transcript_tool_run_state_collapsed else Res.string.a11y_transcript_tool_run_state_expanded,
    )
    // UIUX §-QA fix: an error run defaults OPEN, so the error clause must be in the a11y in BOTH states — else a
    // screenreader on an open error header hears only "N steps", not "✗ N failed" (Pre-Read parity, SR == sighted).
    // A clean run: collapsed carries "eingeklappt"; expanded is the neutral count (children own their own a11y).
    val cd = when {
        // CYP-795: error runs default OPEN, so the OPEN error header uses its own string — carries the error count
        // but WITHOUT "collapsed"/"eingeklappt" (which the collapsed_with_errors string would falsely announce).
        run.hasError && !collapsed ->
            stringResource(Res.string.a11y_transcript_tool_run_open_with_errors, steps, run.errorCount)
        run.hasError -> stringResource(Res.string.a11y_transcript_tool_run_collapsed_with_errors, steps, run.errorCount)
        collapsed -> stringResource(Res.string.a11y_transcript_tool_run_collapsed, steps)
        else -> stringResource(Res.string.transcript_tool_run_collapsed, steps)
    }
    val stepsLabel = stringResource(Res.string.transcript_tool_run_collapsed, steps)
    val contentColor =
        if (receded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    TranscriptRow(agentId = agentId, index = run.startIndex, tsMs = run.events.first().tsMs, receded = receded) {
        Box(modifier = Modifier.testTag(AgentViewTags.toolRun(agentId, run.startIndex))) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AgentViewTags.toolRunToggle(agentId, run.startIndex))
                    .clickable(onClickLabel = toggleLabel, role = Role.Button, onClick = onToggle)
                    .semantics {
                        role = Role.Button
                        stateDescription = stateDesc
                        contentDescription = cd
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (collapsed) "▸" else "▾",
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                    modifier = Modifier.clearAndSetSemantics {},
                )
                Text(stepsLabel, style = MaterialTheme.typography.labelMedium, color = contentColor)
                if (run.hasError) {
                    Text(
                        "✗ " + stringResource(Res.string.transcript_tool_run_errors, run.errorCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(AgentViewTags.toolRunErrors(agentId, run.startIndex)),
                    )
                }
            }
        }
    }
}

/**
 * CYP-381 §7.1 — the DURABLE transcript discontinuity band: a full-width WARN landmark that marks where the agent's
 * memory begins. Reuses the Event-Log `GapRow` idiom (leading rail + glyph + label on a container) but **WARN, not
 * error** — the session is fine, only its memory isn't. The glyph is the SAME `∅` as the titlebar CONTEXT_LOST
 * marker (one vocabulary across title bar and transcript). Rail + glyph are decorative (`clearAndSetSemantics`); the
 * band is one static a11y landmark (`a11y_transcript_context_lost`, merged, NO `liveRegion` HERE — the live
 * announcement IS the window banner, once: the CONTEXT_LOST [FrameBanner] carries `liveRegion = Assertive` (CYP-836),
 * so this durable landmark stays focus-readable and is not re-announced). Persists after the live state recovers to
 * MEDIATED (§7.1.2 recovery-persistence).
 */
@Composable
private fun TranscriptDiscontinuityRow(agentId: String) {
    val (container, onContainer) = severityContainer(Severity.WARN)
    val landmark = stringResource(Res.string.a11y_transcript_context_lost)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AgentViewTags.contextLostDivider(agentId))
            .background(container)
            .semantics(mergeDescendants = true) { contentDescription = landmark }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(4.dp).height(22.dp)
                .background(severityColor(Severity.WARN))
                .clearAndSetSemantics {},
        )
        Text("∅", style = MaterialTheme.typography.labelMedium, color = onContainer, modifier = Modifier.clearAndSetSemantics {})
        Text(
            text = stringResource(Res.string.transcript_context_lost),
            style = MaterialTheme.typography.labelMedium,
            color = onContainer,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** CYP-335 (`transcript.time.column.width`): fixed, not intrinsic — an `IntrinsicSize.Min` would be
 *  re-measured per recycled `LazyColumn` item instead of shared across them. 5 monospace glyphs at
 *  `labelSmall` ≈ 33 dp, so ~33 % reserve. */
private val TRANSCRIPT_TIME_COLUMN_WIDTH = 44.dp

/** CYP-335 (`transcript.time.column.gap`): reuses the row's existing 8 dp horizontal unit. */
private val TRANSCRIPT_TIME_COLUMN_GAP = 8.dp

/**
 * CYP-335: one transcript line — the `HH:mm` gutter in local time, then the kind-specific row.
 *
 * The gutter belongs to the *transcript*, not to the six rows. Wrapping them (rather than threading a
 * timestamp into each) is what makes "every line kind shows a time" structurally true — a future
 * [AgentEvent] subtype gets the gutter for free, and no row can silently opt out. It also keeps all six row
 * bodies bit-identical: their indents, markers and test tags never move.
 *
 * Two consequences worth naming, because they are the design (spec §1):
 *  - The content edge is the same for all six kinds (`contentPadding 12 + 44 + 8 = 64 dp`).
 *  - [ResultRow]'s tinted container starts **after** the gutter, so the time always sits on `surface` and
 *    never on `errorContainer`/`surfaceVariant`. One contrast pair to prove instead of three.
 *
 * **Two clocks feed this one column.** Stream rows carry the *server's* `tsMs`; [AgentEvent.UserTurn] and the
 * `conn-error` [AgentEvent.Notice] carry the *client's* (see [AgentViewModel]'s injected clock). If the two
 * drift, the column can run backwards — the same failure class as re-dating a row in [foldEvent], only across
 * processes instead of across updates. In the MVP the server binds `localhost`, so drift is ~0; it turns real
 * with the first remote connector.
 *
 * The fix is **not** an estimate. Event timestamps only *lower-bound* the server's now, so deriving a skew from
 * them mis-dates a turn by the agent's idle time — hours wrong on a perfectly correct clock (measured; see
 * [AgentViewModel]). Today a fast browser therefore inverts the column: the reply renders below the question
 * with an earlier time. It needs the server to state its own `serverNowMs` when the client attaches — only the
 * *send* instant is replay-immune. Tracked as CYP-346, in `:core` + server, not in this module.
 */
@Composable
private fun TranscriptRow(
    agentId: String,
    index: Int,
    tsMs: Long,
    /** CYP-381 §7.1: this row is forgotten history (above the CONTEXT_LOST landmark). Draws a 2.dp `outlineVariant`
     *  gutter rail (decorative, `drawBehind` — no layout shift, no text-contrast change; the row's own colours stay
     *  AA — "recede, stay legible", the codebase's no-text-alpha rule). The role-demotion is inside [AssistantTextRow]. */
    receded: Boolean = false,
    content: @Composable () -> Unit,
) {
    val railColor = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (receded) Modifier.drawBehind {
                    // A 2.dp vertical rule at the left of the gutter lane, spanning the row height (decorative rail).
                    drawRect(color = railColor, topLeft = Offset.Zero, size = Size(2.dp.toPx(), size.height))
                } else Modifier,
            ),
    ) {
        TimeCell(agentId = agentId, index = index, tsMs = tsMs)
        Spacer(Modifier.width(TRANSCRIPT_TIME_COLUMN_GAP))
        // alignByBaseline on BOTH children: the time is labelSmall (11 sp) and the content bodyMedium (14 sp),
        // so aligning the *boxes* (Alignment.Top) would leave the smaller digits visibly floating above the
        // content's baseline — and drift differently per row kind, which run three typography steps.
        Box(Modifier.weight(1f).alignByBaseline()) { content() }
    }
}

/**
 * The `HH:mm` cell. Visible text ≠ spoken text (the file's idiom, cf. [ResultRow]/[UserTurnRow]): the eye sees
 * `09:14`, the screen reader hears "um 09:14 Uhr" — bare digits at the end of an arbitrary user message would
 * be meaningless.
 *
 * `colorScheme.outline` is deliberately NOT used here despite being the muted role next door in [NoticeRow]:
 * against `surface` it measures 3.55:1 (light) / 3.63:1 (dark) and fails WCAG AA for text. It is a border role.
 * `onSurfaceVariant` measures 8.69:1 / 9.80:1 (AAA) and needs no alpha — damping comes from size and role.
 *
 * The test tag lives INSIDE `clearAndSetSemantics` because that is what the design spec's tag contract asks for,
 * and because it is order-independent. Measured, not assumed: on Compose 1.9 / Kotlin 2.4 a `Modifier.testTag(…)`
 * placed *before* the block **survives** it — the documented "the clearing swallows the tag" failure mode did not
 * reproduce here (`TranscriptTimestampRenderTest` stays green either way). So the placement is insurance against a
 * behaviour we do not control, not a fix for an observed break. What the test does pin is that the cell is
 * addressable by tag AND announces the labelled description rather than bare digits.
 */
@Composable
private fun RowScope.TimeCell(agentId: String, index: Int, tsMs: Long) {
    // The offset lookup crosses into JS on Wasm — resolve it per instant, not per recomposition.
    val clock = remember(tsMs) { formatLocalHhMm(tsMs) }
    val spoken = stringResource(Res.string.a11y_transcript_time, clock)
    val tag = AgentViewTags.eventTime(agentId, index)
    Text(
        text = clock,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.End,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .width(TRANSCRIPT_TIME_COLUMN_WIDTH)
            .alignByBaseline()
            .clearAndSetSemantics {
                contentDescription = spoken
                testTag = tag
            },
    )
}

@Composable
private fun AssistantTextRow(
    event: AgentEvent.AssistantText,
    modifier: Modifier = Modifier,
    /** CYP-381 §7.1: forgotten history → demote the loudest role `onSurface` (15.6:1) to `onSurfaceVariant`
     *  (8.7:1, still comfortably AA) — "quiet = role, never text-alpha". Other row kinds are already quiet
     *  (`onSurfaceVariant`/`secondary`) and stay as-is (demoting further risks AA). */
    receded: Boolean = false,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = event.text,
            color = if (receded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!event.complete) {
            // The cursor is a glyph; expose "Agent is typing" to the screen reader instead (CYP-23).
            val streamingDesc = stringResource(Res.string.a11y_assistant_streaming)
            Text(
                text = "▌",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clearAndSetSemantics { contentDescription = streamingDesc },
            )
        }
    }
}

@Composable
private fun ToolCallRow(event: AgentEvent.ToolCall, modifier: Modifier = Modifier) {
    val (glyph, tint) = when (event.status) {
        ToolStatus.RUNNING -> "⟳" to MaterialTheme.colorScheme.onSurfaceVariant // a0: in-progress neutral; OK stays primary (blue)
        ToolStatus.OK -> "✓" to MaterialTheme.colorScheme.primary
        ToolStatus.ERROR -> "✗" to MaterialTheme.colorScheme.error
    }
    // Glyph + colour alone aren't screen-reader accessible; announce the status as text (CYP-23).
    // Disclosure-true: OK = "ausgeführt", not "erfolgreich".
    val statusDescription = when (event.status) {
        ToolStatus.RUNNING -> stringResource(Res.string.a11y_tool_running, event.tool)
        ToolStatus.OK -> stringResource(Res.string.a11y_tool_ok, event.tool)
        ToolStatus.ERROR -> stringResource(Res.string.a11y_tool_error, event.tool)
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = glyph,
            color = tint,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clearAndSetSemantics { contentDescription = statusDescription },
        )
        Text(
            text = "${event.tool}(${event.summary})",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ResultRow(event: AgentEvent.Result, modifier: Modifier = Modifier) {
    val container =
        if (event.isError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceVariant
    val content =
        if (event.isError) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    // Disclosure-true: success = "Turn abgeschlossen", not "erledigt" (CYP-23).
    // M1 (UIUX): the success `label` carries substance (masked tool-result content), so expose it to
    // the screen reader too — Equal Access — appended after the disclosure-safe base.
    val resultDescription =
        if (event.isError) {
            stringResource(Res.string.a11y_result_error, event.label)
        } else {
            val base = stringResource(Res.string.a11y_result_success)
            if (event.label.isBlank()) base else "$base — ${event.label}"
        }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(container)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = event.label,
            color = content,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clearAndSetSemantics { contentDescription = resultDescription },
        )
    }
}

@Composable
private fun IncomingSystemRow(event: AgentEvent.IncomingSystem, modifier: Modifier = Modifier) {
    // CYP-326 #1: a PLATFORM-injected incoming message (e.g. the compact orchestrator's `/compact`), styled
    // `onSurfaceVariant` with a visible "System" label + a decorative leading `⇥` — distinct from the assistant
    // (`onSurface`) and from the operator's own composer turn (CYP-323 `secondary` + `›`). The raw injected text
    // is shown verbatim (truthful — `/compact` reads as what was sent). The `⇥` and the "System" label are
    // decorative/cleared; the row carries an invisible "System: …" content description for the screen reader.
    val systemLabel = stringResource(Res.string.transcript_system_label)
    val systemDescription = stringResource(Res.string.a11y_transcript_system, event.text)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "⇥",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clearAndSetSemantics {}, // decorative marker — hidden from the screen reader
        )
        Text(
            text = systemLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.clearAndSetSemantics {}, // visible label; SR meaning is carried by the row description
        )
        Text(
            text = event.text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clearAndSetSemantics { contentDescription = systemDescription },
        )
    }
}

@Composable
private fun UserTurnRow(event: AgentEvent.UserTurn, agentId: String, index: Int, modifier: Modifier = Modifier) {
    // CYP-323: the human turn, set slightly apart with `colorScheme.secondary` text (role-bound, follows the theme).
    // WCAG 1.4.1 — colour is never the sole discriminator: a subtle leading `›` marks EVERY user row (and no other
    // turn). The `›` is purely visual → cleared from semantics (decorative/hidden); the row instead carries an
    // invisible "Deine Nachricht: …" content description so a screen reader still distinguishes the human turn.
    // F4 (CYP-580): a turn composed while NOT connected carries an honest "nicht zugestellt" marker (never rendered
    // as sent); colour is never the sole carrier (a worded label + a11y), and it recedes (onSurfaceVariant, WCAG-safe).
    val userTurnDescription = stringResource(Res.string.a11y_user_turn, event.text)
    val undeliveredLabel = if (!event.delivered) stringResource(Res.string.agent_turn_undelivered) else null
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "›",
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clearAndSetSemantics {}, // decorative second signal — hidden from the screen reader
        )
        Text(
            text = event.text,
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clearAndSetSemantics { contentDescription = userTurnDescription },
        )
        if (undeliveredLabel != null) {
            // UIUX2 honesty-gate: the marker fires on UNCONFIRMED (composed while not LIVE ⇒ buffered, may still
            // deliver on reconnect), not on a KNOWN drop — so the copy is "delivery not confirmed", never the
            // definitive "not delivered". The a11y description names the object (not a free-floating phrase).
            val undeliveredA11y = stringResource(Res.string.a11y_agent_turn_undelivered)
            Text(
                text = undeliveredLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant, // WCAG 1.4.3-safe (8.69:1/9.80:1), quieter than content
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .testTag(AgentViewTags.userTurnUndelivered(agentId, index))
                    .clearAndSetSemantics { contentDescription = undeliveredA11y },
            )
        }
    }
}

/**
 * CYP-385 — the tone ROLE a system notice renders in. A **failure** notice (connection lost, turn error) MUST be
 * visually distinct from a neutral INFO notice: "Verbindung zum Agenten verloren" in the neutral `onSurfaceVariant`
 * reads like "alles ok" — the CYP-760 / CYP-643 honesty class (meaning lost when the error tone is absent). A pure
 * decision (like [statusDotSpec]) so the distinction is toothable without a pixel compare.
 */
internal enum class NoticeToneRole { NEUTRAL, ERROR }

internal fun noticeToneRole(isError: Boolean): NoticeToneRole =
    if (isError) NoticeToneRole.ERROR else NoticeToneRole.NEUTRAL

@Composable
private fun NoticeRow(event: AgentEvent.Notice, modifier: Modifier = Modifier) {
    val noticeDescription = stringResource(Res.string.a11y_notice, event.text)
    // CYP-385: an ERROR notice (conn-lost, turn error) takes the distinct `error` tone; a neutral notice keeps
    // `onSurfaceVariant`. The two MUST differ so a connection loss can never read as neutral (Cyp385NoticeErrorTone*).
    // WCAG 1.4.1 holds either way — the WORDING carries the meaning ("… verloren" / "Turn-Fehler"), colour only
    // reinforces. Both tokens are AA as TEXT on `surface`: onSurfaceVariant 8.69:1 / 9.80:1 (CYP-337; still quieter
    // than the content colour onSurface 15.6:1 — `outline` was never needed to sound soft), `error` per the M3 role.
    val color = when (noticeToneRole(event.isError)) {
        NoticeToneRole.ERROR -> MaterialTheme.colorScheme.error
        NoticeToneRole.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = event.text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = noticeDescription },
    )
}

/**
 * CYP-743 — the hint [HintTone] per composer state, so "unknown" never reads as "denied" at the glyph/colour
 * (which land BEFORE the text — the visible half of the safe-but-silent discipline). [AgentComposerWritability.READ_ONLY]
 * → [HintTone.GATED] (`·`, a permission gate, not a fault); [AgentComposerWritability.UNKNOWN] → [HintTone.ERROR]
 * (`✕`, a fail-closed block). The two MUST differ — the tooth pins it. UNGATED/WRITABLE render the editable
 * composer (no hint) → defensive [HintTone.INFO], never reached.
 */
internal fun agentComposerHintTone(writability: AgentComposerWritability): HintTone = when (writability) {
    AgentComposerWritability.READ_ONLY -> HintTone.GATED
    AgentComposerWritability.UNKNOWN -> HintTone.ERROR
    AgentComposerWritability.UNGATED, AgentComposerWritability.WRITABLE -> HintTone.INFO
}

@Composable
private fun MessageComposer(
    agentId: String,
    onSend: (String) -> Unit,
    // CYP-387: newest-last snapshot of messages sent to this agent (the immutable store), read lazily on each
    // arrow press. Default empty keeps existing call sites / tests unchanged (no recall = the old behaviour).
    history: () -> List<String> = { emptyList() },
    // CYP-738: the send-writability tri-state. Default [AgentComposerWritability.UNGATED] = editable, byte-identical
    // for existing call sites / render tests (the feature is dormant until the AgentWritableApi seam is wired).
    writability: AgentComposerWritability = AgentComposerWritability.UNGATED,
    modifier: Modifier = Modifier,
) {
    // CYP-738 tri-state (mirrors CommPanel's CYP-273 composer). Render precedence before the editable body:
    //   READ_ONLY → the proactive read-only hint (you may read this agent, just not message it), no input.
    //   UNKNOWN   → disabled-with-hint UNCONDITIONALLY — the write-right could not be determined (endpoint error /
    //               pre-deploy). NEVER silently editable: a wired runtime-unknown must not reuse the UNGATED path
    //               (the §4a leak the PL guardrail forbids). Distinct surface + string from READ_ONLY.
    //   UNGATED / WRITABLE → the editable composer below (UNGATED = dormant/old path; WRITABLE = gated-and-allowed).
    if (writability == AgentComposerWritability.READ_ONLY) {
        // CYP-743: GATED tone (·) — a permission gate, not an error. Distinct from UNKNOWN's ERROR so the glyph/
        // colour (which land before the text) never make "no write access" read as a fault.
        TonedHint(
            text = stringResource(Res.string.agent_composer_readonly_hint),
            tone = agentComposerHintTone(writability),
            tag = AgentViewTags.composerReadonly(agentId),
            modifier = modifier.padding(12.dp),
        )
        return
    }
    if (writability == AgentComposerWritability.UNKNOWN) {
        // CYP-743: ERROR tone (✕) — a fail-closed block (write-right couldn't be checked). Deliberately NOT the
        // GATED tone: "unknown" must not read as "denied" (the visible half of the safe-but-silent discipline).
        TonedHint(
            text = stringResource(Res.string.agent_composer_unknown_hint),
            tone = agentComposerHintTone(writability),
            tag = AgentViewTags.composerUnknown(agentId),
            modifier = modifier.padding(12.dp),
        )
        return
    }
    var draft by remember { mutableStateOf("") }
    // CYP-387 §2 — the recall cursor (pure state machine; see ComposerRecall). Per agent window.
    val recall = remember { ComposerRecall() }

    fun submit() {
        if (draft.isNotBlank()) {
            // §2.2: a sent recall-edit is captured as a new NEWEST entry (via onSend → InputHistory.record); the
            // original stays. Then leave history: draft cleared, cursor back at the (now empty) live draft.
            onSend(draft)
            draft = ""
            recall.reset()
        }
    }

    // CYP-26 §2.2 / CYP-370: keep the input usable when the window is narrow. Below the compact threshold the
    // "Senden" label degrades to a glyph (a11y name preserved) so nothing truncates. (The old widthIn(min) on the
    // input was dead and removed in CYP-370; the breakpoint, not a min width, is what keeps the field usable.)
    //
    // CYP-375 — ACCEPTED non-monotonicity, documented not smoothed. As the window widens past the breakpoint the
    // input field briefly SHRINKS (measured 288 -> 250 dp at 376), because the send button reclaims ~42 dp to
    // render "Senden" as a WORD instead of the glyph. It cannot be tuned away: at any switch width W the
    // glyph-input (W-60) and the word-input (W-102) differ by the send-button delta no matter where the threshold
    // sits — moving it relocates the drop, never removes it. Reserving the word width always would take those
    // 42 dp from the input in exactly the narrow band the breakpoint exists to protect. So the input's SHARE
    // jumps; the composer's total content (input + send) stays monotonic — nothing is lost, only re-split, and
    // `Cyp375ComposerContentMonotonicityTest` pins that. (CommPanel's composer carries the identical structure.)
    BoxWithConstraints(modifier = modifier) {
        val compact = maxWidth < COMPOSER_COMPACT_INPUT_THRESHOLD.dp + 96.dp
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                // Typing while navIndex != null is a transient working copy (spec §2.2) — the store is untouched.
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f).testTag(AgentViewTags.input(agentId))
                    // §0/§2.1: single-line field → ↑/↓ are pure history nav (WindowManager.kt:604 pattern). Preview
                    // so the field's own key handling never swallows them first; only a real recall consumes it.
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val recalled = when (e.key) {
                            Key.DirectionUp -> recall.older(history(), draft)
                            Key.DirectionDown -> recall.newer(history(), draft)
                            else -> return@onPreviewKeyEvent false
                        }
                        if (recalled != null) { draft = recalled; true } else false
                    },
                // Placeholder degrades by ellipsis, never character-wrap, in a narrow field.
                placeholder = { Text("Nachricht an den Agenten…", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                singleLine = true,
            )
            Button(
                onClick = { submit() },
                modifier = Modifier
                    .testTag(AgentViewTags.sendBtn(agentId))
                    .then(if (compact) Modifier.semantics { contentDescription = "Senden" } else Modifier),
            ) {
                Text(if (compact) "➤" else "Senden")
            }
        }
    }
}
