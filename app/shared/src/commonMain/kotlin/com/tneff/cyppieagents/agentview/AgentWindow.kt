package com.tneff.cyppieagents.agentview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.connector.ConnectorCapabilityBadge
import com.tneff.cyppieagents.connector.ConnectorProviderChip
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.ProviderInfo
import com.tneff.cyppieagents.testing.testTagA11y
import com.tneff.cyppieagents.window.COMPOSER_MIN_WIDTH
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
import kmpcyppieagents.app.shared.generated.resources.a11y_user_turn
import kmpcyppieagents.app.shared.generated.resources.transcript_system_label
import kmpcyppieagents.app.shared.generated.resources.agent_ctl_err_already_running
import kmpcyppieagents.app.shared.generated.resources.agent_ctl_err_generic
import kmpcyppieagents.app.shared.generated.resources.agent_ctl_err_operator_required
import kmpcyppieagents.app.shared.generated.resources.agent_ctl_err_spawn_failed
import kmpcyppieagents.app.shared.generated.resources.agent_ctl_restart
import kmpcyppieagents.app.shared.generated.resources.agent_ctl_start
import kmpcyppieagents.app.shared.generated.resources.agent_ctl_stop
import com.tneff.cyppieagents.comm.ConnectionStatus
import kmpcyppieagents.app.shared.generated.resources.agent_reconnecting
import kmpcyppieagents.app.shared.generated.resources.agent_status_error
import kmpcyppieagents.app.shared.generated.resources.agent_status_running
import kmpcyppieagents.app.shared.generated.resources.agent_status_starting
import kmpcyppieagents.app.shared.generated.resources.agent_status_restarting
import kmpcyppieagents.app.shared.generated.resources.agent_status_stopped
import kmpcyppieagents.app.shared.generated.resources.agent_status_unknown
import kmpcyppieagents.app.shared.generated.resources.terminal_mode_orchestration
import kmpcyppieagents.app.shared.generated.resources.terminal_mode_shell
import kmpcyppieagents.app.shared.generated.resources.terminal_shell_note
import kmpcyppieagents.app.shared.generated.resources.terminal_gated_pending
import kmpcyppieagents.app.shared.generated.resources.a11y_terminal_mode
import kmpcyppieagents.app.shared.generated.resources.workspace_operator_only
import org.jetbrains.compose.resources.stringResource

/**
 * The agent window: a scrolling transcript of [AgentEvent]s over a "message to the agent" composer, with a
 * CYP-333 content-view toggle to a real Terminal (a fresh PTY over `/ws/terminal`, CYP-332/334).
 *
 * [agentId] parameterizes the test tags per the v0.2 Test-Contract (`agent.<agentId>.stream` etc.).
 *
 * **CYP-333 scope (honest):** the toggle is a *client view-selection* (Orchestrierung ↔ Shell) — NOT the spec's
 * backend hand-off. The mediated stream-json session keeps running while the shell is shown, so no hub-blind
 * banner / frozen token / CONTEXT_LOST is claimed here (that is the Backend follow-up, ⟂BE-1..3). The interim
 * second view (Auftraggeber ruling) is an honest **worktree bash shell** (CYP-348), not a second `claude`; the
 * same-session claude terminal arrives later with the hand-off (BE-2).
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
     * CYP-333: when `true` AND no [terminalContent] is wired, the Shell segment is disabled with an honest
     * "available once the worktree-shell backend lands" note (for an operator). With CYP-348 landed the shell is
     * normally **live** (a safe `bash -l` worktree shell, not a second `claude`); this gated state is the
     * exceptional case — a non-Desktop target or the [WORKTREE_SHELL_LIVE_ENABLED] kill-switch turned off.
     * `terminalContent` present = live; `terminalGatedNote` = honestly gated.
     */
    terminalGatedNote: Boolean = false,
) {
    val transcript by viewModel.transcript.collectAsState()
    val lifecycle by viewModel.lifecycleState.collectAsState()
    val startPending by viewModel.startPending.collectAsState()
    val restartPending by viewModel.restartPending.collectAsState()
    val lifecycleError by viewModel.lifecycleError.collectAsState()
    val connection by viewModel.connection.collectAsState()
    val contentMode by viewModel.contentMode.collectAsState()
    Column(modifier = modifier.fillMaxSize()) {
        lifecycleError?.let { code -> LifecycleErrorRow(agentId, code) }
        AgentHeader(
            agentId = agentId,
            state = lifecycle,
            startPending = startPending,
            restartPending = restartPending,
            connection = connection,
            canControl = viewModel.canControl,
            onStart = viewModel::start,
            onStop = viewModel::stop,
            onRestart = viewModel::restart,
            capabilities = capabilities,
            capabilitiesLoading = capabilitiesLoading,
            provider = provider,
            onCapabilityBadgeClick = onCapabilityBadgeClick,
        )
        // CYP-333: the mode toggle lives in a FRAME row above the content rectangle. Z-order (04 §5): the Desktop
        // terminal is a `SwingPanel` that renders OVER the Compose layer, so chrome must frame it, never overlay it.
        ModeToggleRow(
            agentId = agentId,
            mode = contentMode,
            canControl = viewModel.canControl,
            terminalAvailable = terminalContent != null,
            terminalGatedNote = terminalGatedNote,
            onModeChange = viewModel::showContentMode,
        )
        // The content rectangle (04 §5): transcript OR terminal, weight 1f. The terminal occupies ONLY this
        // rectangle — no Compose chrome is z-stacked over it.
        Box(modifier = Modifier.weight(1f).fillMaxWidth().testTag(AgentViewTags.content(agentId))) {
            when (contentMode) {
                AgentContentMode.ORCHESTRATION ->
                    AgentTranscript(agentId = agentId, events = transcript, modifier = Modifier.fillMaxSize())
                AgentContentMode.TERMINAL ->
                    // Defensive: the toggle disables the Terminal segment when no terminal is wired, so this
                    // branch is normally unreachable without [terminalContent]; fall back to the transcript.
                    terminalContent?.invoke(agentId, Modifier.fillMaxSize())
                        ?: AgentTranscript(agentId = agentId, events = transcript, modifier = Modifier.fillMaxSize())
            }
        }
        // The mediated composer belongs to the Orchestrierung view only — the terminal has its own input. It is
        // NOT a "mediation is off" claim (the session runs); it just isn't shown while you look at the terminal.
        if (contentMode == AgentContentMode.ORCHESTRATION) {
            MessageComposer(
                agentId = agentId,
                onSend = viewModel::onSend,
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
) {
    val orchLabel = stringResource(Res.string.terminal_mode_orchestration)
    // Interim (Auftraggeber ruling): the second view is an honest worktree SHELL (bash), not the agent's claude
    // terminal — so the segment reads "Shell". The claude "Terminal" meaning arrives with the hand-off (BE-2).
    val termLabel = stringResource(Res.string.terminal_mode_shell)
    val currentLabel = if (mode == AgentContentMode.TERMINAL) termLabel else orchLabel
    val viewDescription = stringResource(Res.string.a11y_terminal_mode, currentLabel)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .testTag(AgentViewTags.modeToggle(agentId))
                // Announces the current view (spec §8 `a11y_terminal_mode`); a plain description does not merge
                // the child segments, so each SegmentedButton still speaks its own label + selected state.
                .semantics { contentDescription = viewDescription },
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
            // Non-operator: honest read-only disclosure (reuse `workspace_operator_only`, CYP-317 no-fake-switch).
            !canControl -> Text(
                text = stringResource(Res.string.workspace_operator_only),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(AgentViewTags.modeToggleGateHint(agentId)),
            )
            // Live Shell view active → honest descriptor: this is a bash worktree shell, NOT the agent's session
            // (the claude same-session terminal reuses the slot later, BE-2). Prevents mistaking it for the agent.
            mode == AgentContentMode.TERMINAL && terminalAvailable -> Text(
                text = stringResource(Res.string.terminal_shell_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(AgentViewTags.modeToggleShellNote(agentId)),
            )
            // Operator, but no live shell backend (non-Desktop target / kill-switch off) → say WHY the Shell
            // segment is off, rather than a silently-disabled control. Neutral tone (a deferral, not an error).
            terminalGatedNote && !terminalAvailable -> Text(
                text = stringResource(Res.string.terminal_gated_pending),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(AgentViewTags.modeToggleTerminalGated(agentId)),
            )
        }
    }
}

/** Honest surfacing of a lifecycle-control failure (CYP-73) — the server's reason, not a generic blur. */
@Composable
private fun LifecycleErrorRow(agentId: String, code: String) {
    val text = when (code) {
        "already_running" -> stringResource(Res.string.agent_ctl_err_already_running)
        "spawn_failed" -> stringResource(Res.string.agent_ctl_err_spawn_failed)
        "operator_required" -> stringResource(Res.string.agent_ctl_err_operator_required)
        else -> stringResource(Res.string.agent_ctl_err_generic)
    }
    Text(
        text = text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag(AgentViewTags.lifecycleError(agentId)),
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
    /** CYP-262: a Start request is in flight → the status shows the transient "Startet…" (client-only). */
    startPending: Boolean = false,
    /** CYP-330: a Restart request is in flight → the status shows the transient "Neustart…" (client-only). */
    restartPending: Boolean = false,
    connection: ConnectionStatus = ConnectionStatus.LIVE,
    capabilities: Capabilities? = null,
    capabilitiesLoading: Boolean = false,
    provider: ProviderInfo? = null,
    onCapabilityBadgeClick: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(AgentViewTags.header(agentId))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusIndicator(agentId, state, startPending, restartPending)
        // CYP-204: reconnecting indicator — present ONLY while the per-agent WS is not LIVE (the adapter is
        // auto-reconnecting from the seq cursor; on reconnect the server replays the history gapless). Its OWN
        // axis, next to but distinct from the lifecycle status (process state ≠ socket state).
        ReconnectingChip(agentId, connection)
        // Provider axis (CYP-137) — the subordinate "(Claude)" qualifier next to the identity/status, its OWN
        // marker (≠ fidelity, ≠ lifecycle). Present only when known (fail-closed by absence); neutral, no hue.
        ConnectorProviderChip(provider = provider, agentId = agentId)
        // Fidelity axis (CYP-123) — its own marker next to the lifecycle status, NOT mixed into it. Present
        // only when degraded / not-yet-reported (fail-closed by absence); opens the capability panel.
        ConnectorCapabilityBadge(caps = capabilities, agentId = agentId, onClick = onCapabilityBadgeClick, loading = capabilitiesLoading)
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = onStart,
            enabled = canControl && state != AgentLifecycleState.RUNNING,
            modifier = Modifier.testTag(AgentViewTags.startBtn(agentId)),
        ) { Text(stringResource(Res.string.agent_ctl_start)) }
        TextButton(
            onClick = onStop,
            enabled = canControl && state == AgentLifecycleState.RUNNING,
            modifier = Modifier.testTag(AgentViewTags.stopBtn(agentId)),
        ) { Text(stringResource(Res.string.agent_ctl_stop)) }
        TextButton(
            onClick = onRestart,
            // CYP-330: enabled for any operator — Restart must not be hard-ineffective in UNKNOWN/STOPPED (a
            // stopped/unknown agent is exactly when you want to bring it back). The server stays authoritative and
            // surfaces an honest reason if the transition is invalid; the "Neustart…" transient acknowledges the click.
            enabled = canControl,
            modifier = Modifier.testTag(AgentViewTags.restartBtn(agentId)),
        ) { Text(stringResource(Res.string.agent_ctl_restart)) }
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

@Composable
private fun StatusIndicator(agentId: String, state: AgentLifecycleState, startPending: Boolean = false, restartPending: Boolean = false) {
    // CYP-262/330 Teil 1: while a Start/Restart request is in flight (client-only, before the server's event),
    // show the honest transient "Startet…"/"Neustart…" instead of the resolved state — NEVER a resolved label
    // before the server confirms it (§9-1). Both flags always resolve on the next lifecycle event, so neither can
    // stick. Same node/tag (0 new tag) and the in-progress NEUTRAL tone (CYP-300 a0: onSurfaceVariant). The
    // "Neustart…" flash is the visible acknowledgement of a RUNNING→RUNNING restart (the swallowed-click fix).
    val pending = startPending || restartPending
    val label = when {
        startPending -> stringResource(Res.string.agent_status_starting)
        restartPending -> stringResource(Res.string.agent_status_restarting)
        else -> when (state) {
            AgentLifecycleState.RUNNING -> stringResource(Res.string.agent_status_running)
            AgentLifecycleState.STOPPED -> stringResource(Res.string.agent_status_stopped)
            AgentLifecycleState.ERROR -> stringResource(Res.string.agent_status_error)
            AgentLifecycleState.UNKNOWN -> stringResource(Res.string.agent_status_unknown)
        }
    }
    val dotColor = if (pending) MaterialTheme.colorScheme.onSurfaceVariant else when (state) { // a0: neutral, distinct from RUNNING=primary
        AgentLifecycleState.RUNNING -> MaterialTheme.colorScheme.primary
        AgentLifecycleState.STOPPED -> MaterialTheme.colorScheme.outline
        AgentLifecycleState.ERROR -> MaterialTheme.colorScheme.error
        AgentLifecycleState.UNKNOWN -> MaterialTheme.colorScheme.outlineVariant
    }
    val description = stringResource(Res.string.a11y_agent_status, label)
    Row(
        modifier = Modifier
            .testTag(AgentViewTags.status(agentId))
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Colour is never the sole signal (WCAG 1.4.1): the text label carries the meaning; the dot
        // only reinforces it.
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
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
) {
    val listState = rememberLazyListState()
    // Pin to bottom as new events arrive (streaming feel). A more refined version would release
    // the pin while the user scrolls up; deferred until the window manager (CYP-3) lands.
    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) listState.animateScrollToItem(events.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.testTagA11y(AgentViewTags.stream(agentId)),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(events, key = { _, event -> event.id }) { index, event ->
            // CYP-335: the `HH:mm` gutter wraps EVERY line kind — one place, so no row type can be forgotten.
            TranscriptRow(agentId = agentId, index = index, tsMs = event.tsMs) {
                when (event) {
                    is AgentEvent.AssistantText -> AssistantTextRow(
                        event,
                        Modifier.testTagA11y(AgentViewTags.event(agentId, index, EventKind.ASSISTANT_TEXT)),
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
                        Modifier.testTagA11y(AgentViewTags.event(agentId, index, EventKind.USER_TURN)),
                    )
                    is AgentEvent.IncomingSystem -> IncomingSystemRow(
                        event,
                        Modifier.testTagA11y(AgentViewTags.event(agentId, index, EventKind.INCOMING_SYSTEM)),
                    )
                }
            }
        }
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
    content: @Composable () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
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
private fun AssistantTextRow(event: AgentEvent.AssistantText, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = event.text,
            color = MaterialTheme.colorScheme.onSurface,
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
private fun UserTurnRow(event: AgentEvent.UserTurn, modifier: Modifier = Modifier) {
    // CYP-323: the human turn, set slightly apart with `colorScheme.secondary` text (role-bound, follows the theme).
    // WCAG 1.4.1 — colour is never the sole discriminator: a subtle leading `›` marks EVERY user row (and no other
    // turn). The `›` is purely visual → cleared from semantics (decorative/hidden); the row instead carries an
    // invisible "Deine Nachricht: …" content description so a screen reader still distinguishes the human turn.
    val userTurnDescription = stringResource(Res.string.a11y_user_turn, event.text)
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
    }
}

@Composable
private fun NoticeRow(event: AgentEvent.Notice, modifier: Modifier = Modifier) {
    val noticeDescription = stringResource(Res.string.a11y_notice, event.text)
    Text(
        text = event.text,
        // CYP-337: NOT `outline`. Against `surface` it measures 3.55:1 (light) / 3.63:1 (dark) — above WCAG
        // 1.4.11's 3:1 for graphical objects, below 1.4.3's 4.5:1 for text. The same colour is correct as a
        // border and wrong as text. This notice carries its meaning in its wording alone (no glyph, no label),
        // so it is text. `onSurfaceVariant` measures 8.69:1 / 9.80:1 and still reads quieter than the content
        // colour `onSurface` (15.6:1) — `outline` was never needed to sound soft.
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = noticeDescription },
    )
}

@Composable
private fun MessageComposer(
    agentId: String,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    fun submit() {
        if (draft.isNotBlank()) {
            onSend(draft)
            draft = ""
        }
    }
    // CYP-26 §2.2: keep the input usable when the window is narrow. The input holds a min width; below
    // a threshold the "Senden" label degrades to a glyph (a11y label preserved) so nothing is truncated.
    BoxWithConstraints(modifier = modifier) {
        val compact = maxWidth < COMPOSER_MIN_WIDTH.dp + 96.dp
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f).widthIn(min = COMPOSER_MIN_WIDTH.dp).testTag(AgentViewTags.input(agentId)),
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
