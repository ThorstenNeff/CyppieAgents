package com.tneff.cyppieagents.window

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import com.tneff.cyppieagents.agentview.formatLocalHhMm
import com.tneff.cyppieagents.comm.ConnectionStatus
import com.tneff.cyppieagents.eventlog.severityColor
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.model.AgentTerminalControlEvent
import com.tneff.cyppieagents.model.TerminalControlState
import com.tneff.cyppieagents.testing.testTagA11y
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import com.tneff.cyppieagents.ui.TitleBarColors
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_agent_busy
import kmpcyppieagents.app.shared.generated.resources.a11y_terminal_ctl
import kmpcyppieagents.app.shared.generated.resources.a11y_terminal_ctl_held
import kmpcyppieagents.app.shared.generated.resources.a11y_terminal_held_by
import kmpcyppieagents.app.shared.generated.resources.terminal_held_by
import kmpcyppieagents.app.shared.generated.resources.terminal_ctl_context_lost
import kmpcyppieagents.app.shared.generated.resources.terminal_ctl_handing_back
import kmpcyppieagents.app.shared.generated.resources.terminal_ctl_handing_over
import kmpcyppieagents.app.shared.generated.resources.terminal_ctl_interactive
import kmpcyppieagents.app.shared.generated.resources.a11y_agent_context_tokens
import kmpcyppieagents.app.shared.generated.resources.a11y_agent_settings_open
import kmpcyppieagents.app.shared.generated.resources.agent_add
import kmpcyppieagents.app.shared.generated.resources.agent_empty_body
import kmpcyppieagents.app.shared.generated.resources.agent_empty_title
import kmpcyppieagents.app.shared.generated.resources.a11y_pager_dot
import kmpcyppieagents.app.shared.generated.resources.a11y_pager_page
import kmpcyppieagents.app.shared.generated.resources.pager_empty
import kmpcyppieagents.app.shared.generated.resources.a11y_window_fit
import kmpcyppieagents.app.shared.generated.resources.pager_next
import kmpcyppieagents.app.shared.generated.resources.pager_page_position
import kmpcyppieagents.app.shared.generated.resources.pager_prev
import kmpcyppieagents.app.shared.generated.resources.window_fit_action
import kmpcyppieagents.app.shared.generated.resources.a11y_window_expand_key_hint
import kmpcyppieagents.app.shared.generated.resources.a11y_window_restore_key_hint
import kmpcyppieagents.app.shared.generated.resources.window_state_expanded
import kmpcyppieagents.app.shared.generated.resources.window_state_normal
import kmpcyppieagents.app.shared.generated.resources.workspace_operator_only
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-656 substrate — resolve a title-bar window's live-feed connection from the shell's per-agent connection
 * snapshot. An **agent** window returns its real `session.connection`; a **system** window (comm/acl/event-log/…) or
 * an unknown id is absent from the map → **`null` = "no live feed"** (NOT a fabricated [ConnectionStatus.LIVE]:
 * inventing freshness where there is none is exactly the fail-open this bundle removes). Pure so the mapping is
 * unit-testable without a compose render.
 */
internal fun titleBarConnection(connections: Map<String, ConnectionStatus>, windowId: String): ConnectionStatus? =
    connections[windowId]

/**
 * The window host. The layout mode is chosen from the **Compose Window Size Classes** of the measured
 * host (CYP-50/S10, mandated primitive — not `expect`/`actual`):
 *
 * - **Phone-Pager** as soon as **either** axis is `Compact` (phone portrait, or a wide-but-short
 *   landscape phone): the window contents become a snap [HorizontalPager], one page per window.
 * - **Canvas** only when **both** axes are ≥ `Medium` (tablet/desktop): the unchanged floating-window
 *   tiling (CYP-10/16).
 *
 * Each window's body is supplied by [windowContent], so the host stays decoupled from what lives in a
 * window; the same slot feeds both modes. Mode separation is observable by tag: `window.host` exists
 * only in canvas mode, `phonePager.pager` only in pager mode — never both (QA contract, CYP-54).
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun WindowHost(
    state: WindowManagerState,
    modifier: Modifier = Modifier,
    /** User-triggered "fit windows" one-shot re-tile (CYP-26 §2.3); default no-op (e.g. in tests). */
    onFit: () -> Unit = {},
    /**
     * Per-window activity badge (CYP-55); `null` → no badge (fail-closed). Default `{ null }` keeps the
     * host badge-free for callers/tests that don't wire a source — never a regression. The shell derives
     * it from the always-alive badge state. Canvas → title-bar badge; pager → indicator-dot badge.
     */
    badgeFor: (String) -> WindowBadge? = { null },
    /**
     * CYP-316: per-window live context-token count; `null` → no number (fail-closed, §8-8). Default `{ null }`
     * keeps the host number-free for callers/tests that don't wire a source — never a regression. The shell feeds
     * it from the `/ws/token-usage` map (mirrors [badgeFor]). Canvas title bar only — NOT the phone pager (v1, §5).
     */
    contextTokensFor: (String) -> Int? = { null },
    /**
     * CYP-324: per-window live busy flag; `false` → no `*` (fail-closed, unknown ≠ busy). Default `{ false }`
     * keeps the host marker-free for callers/tests that don't wire a source — never a regression. The shell feeds
     * it from the `/ws/busy-state` map (mirrors [contextTokensFor]). Canvas title bar only — NOT the phone pager.
     */
    busyFor: (String) -> Boolean = { false },
    /**
     * CYP-656 substrate — per-window live-feed connection status (the same `session.connection` the body's status
     * dot / reconnecting chip already use), or **`null` = "no live feed"** for a system window / unwired caller.
     * Default **`{ null }` (NOT `{ LIVE }`)**: fail-closed at this broad host boundary — a caller that omits it
     * yields `null`, which the CYP-656 bundle renders as "unknown/grey", NEVER a silently-assumed fresh. `null` is
     * also semantically true (a Settings/Comm window genuinely has no feed), so the default names a real category
     * rather than papering over a forgotten wire. The shell feeds it from the hoisted per-agent `AgentViewModel`s
     * (mirrors [busyFor]).
     *
     * **Substrate only — no behaviour yet.** This threads the connection to the title-bar render scope so the
     * busy-`*` / token-count bundle can gate on it (busy → hide on non-LIVE like the CYP-573 dot; token → grey on
     * non-LIVE, not hidden). Nothing consumes it yet. Canvas title bar only — NOT the phone pager.
     */
    connectionFor: (String) -> ConnectionStatus? = { null },
    /**
     * CYP-354 (client mirror): per-window terminal-control event; `null`/MEDIATED-state → no marker (fail-closed,
     * absent == MEDIATED). Default `{ null }` keeps the host marker-free for callers/tests. The shell feeds it
     * from the `/ws/terminal-state` map (mirrors [busyFor]/[contextTokensFor]). CYP-381: the WHOLE event (not just
     * the enum) so the marker can render holder-identity + since. Read-only mirror; no action here.
     */
    controlEventFor: (String) -> AgentTerminalControlEvent? = { null },
    /** CYP-211: per-window derived titlebar colours (agent identity theming); `null` → default M3 (system windows). */
    titleBarColorsFor: (String) -> TitleBarColors? = { null },
    /** CYP-211: per-window settings opener for the titlebar ⋮ button; `null` → no button (system windows). */
    settingsFor: (String) -> (() -> Unit)? = { null },
    // CYP-216: optional leading titlebar slot (the §5.1 inverted-disc avatar) — a host-injected composable so the
    // window layer stays free of Agent/avatar/comm imports; null → no leading element (e.g. system windows).
    titleBarLeadingFor: (String) -> (@Composable () -> Unit)? = { null },
    // CYP-250: desktop empty-state (0 AGENTS, not 0 windows — the tool windows always coexist). agentsEmpty shows
    // the get-started panel on the canvas background; canAddAgent gates its CTA (operator); onAddFirstAgent routes
    // into the EXISTING openAdd flow (focus agent-mgmt + open its add dialog). Desktop canvas only (not the pager,
    // which has its own zero-windows empty state). Defaults keep every existing caller/test unchanged.
    agentsEmpty: Boolean = false,
    canAddAgent: Boolean = false,
    onAddFirstAgent: () -> Unit = {},
    windowContent: @Composable (WindowState) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Report the measured host size so the canvas can keep windows within the visible area; kept
        // current in both modes so geometry stays valid across a mode switch (CYP-54 §3).
        val widthDp = maxWidth
        val heightDp = maxHeight
        LaunchedEffect(widthDp.value, heightDp.value) {
            state.updateHostSize(widthDp.value, heightDp.value)
        }

        val sizeClass = WindowSizeClass.calculateFromSize(DpSize(widthDp, heightDp))
        val isCompact = sizeClass.widthSizeClass == WindowWidthSizeClass.Compact ||
            sizeClass.heightSizeClass == WindowHeightSizeClass.Compact

        if (isCompact) {
            // The phone pager shows one page per window without a floating titlebar → no ⋮/theming there (CYP-211).
            PhonePager(state = state, badgeFor = badgeFor, windowContent = windowContent)
        } else {
            WindowCanvas(
                state = state, onFit = onFit, badgeFor = badgeFor, contextTokensFor = contextTokensFor, busyFor = busyFor,
                connectionFor = connectionFor,
                controlEventFor = controlEventFor,
                titleBarColorsFor = titleBarColorsFor, settingsFor = settingsFor, titleBarLeadingFor = titleBarLeadingFor,
                agentsEmpty = agentsEmpty, canAddAgent = canAddAgent, onAddFirstAgent = onAddFirstAgent,
                windowContent = windowContent,
            )
        }
    }
}

/**
 * The "desktop" canvas: a full-size surface that stacks every window in [state] by its z-order (list
 * order). Free-floating tiling (CYP-10/16) — only ever shown when both axes are ≥ `Medium`. Hosts the
 * "fit windows" affordance (CYP-26 §2.3): a user-triggered one-shot re-tile, never an automatic one.
 */
@Composable
private fun WindowCanvas(
    state: WindowManagerState,
    onFit: () -> Unit,
    badgeFor: (String) -> WindowBadge?,
    contextTokensFor: (String) -> Int? = { null },
    busyFor: (String) -> Boolean = { false },
    // CYP-656 substrate (no behaviour yet): per-window live-feed connection, threaded to the title bar for the
    // busy-`*`/token bundle to gate on. `null` = no feed (fail-closed, ≠ LIVE). See [WindowHost.connectionFor].
    connectionFor: (String) -> ConnectionStatus? = { null },
    controlEventFor: (String) -> AgentTerminalControlEvent? = { null },
    titleBarColorsFor: (String) -> TitleBarColors? = { null },
    settingsFor: (String) -> (() -> Unit)? = { null },
    // CYP-216: optional leading titlebar slot (the §5.1 inverted-disc avatar) — a host-injected composable so the
    // window layer stays free of Agent/avatar/comm imports; null → no leading element (e.g. system windows).
    titleBarLeadingFor: (String) -> (@Composable () -> Unit)? = { null },
    agentsEmpty: Boolean = false,
    canAddAgent: Boolean = false,
    onAddFirstAgent: () -> Unit = {},
    windowContent: @Composable (WindowState) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().testTagA11y(WindowTestTags.HOST)) {
        // CYP-250: the desktop empty-state (0 agents) is rendered as a FOREGROUND overlay AFTER the window loop
        // below — see the block after `forEachIndexed`. (UIUX §D2 addendum: a background placement is occluded by
        // the always-present tool windows that resetTo/tile lays across the canvas.)
        state.windows.forEachIndexed { index, window ->
            // Key by id so a window keeps its identity (and any internal state) when the list is
            // reordered on focus; graphicsLayer below applies the z-order from the list index.
            key(window.id) {
                FloatingWindow(
                    window = window,
                    isFocused = window.id == state.focusedId,
                    zOrder = index.toFloat(),
                    onFocus = { state.focus(window.id) },
                    onMove = { dx, dy -> state.moveBy(window.id, dx, dy) },
                    onResize = { dWidth, dHeight -> state.resizeBy(window.id, dWidth, dHeight) },
                    // CYP-241: titlebar double-click → Expand+Center / Restore toggle (uses the stored host size,
                    // like onMove/onResize). isExpanded drives the titlebar stateDescription (anchor presence).
                    onToggleExpand = { state.toggleExpand(window.id) },
                    isExpanded = state.isExpanded(window.id),
                    badge = badgeFor(window.id),
                    contextTokens = contextTokensFor(window.id),
                    busy = busyFor(window.id),
                    connection = connectionFor(window.id),
                    control = controlEventFor(window.id),
                    titleBarColors = titleBarColorsFor(window.id),
                    onSettings = settingsFor(window.id),
                    titleBarLeading = titleBarLeadingFor(window.id),
                    content = { windowContent(window) },
                )
            }
        }

        // CYP-250 (UIUX §D2 addendum): the desktop empty-state (active project has 0 AGENTS — keys on [agentsEmpty],
        // NOT state.windows.isEmpty(), since the tool windows always coexist). A FOREGROUND overlay: drawn AFTER the
        // window loop AND lifted above the window stack (windows carry an explicit zIndex = their list index, so draw
        // order alone is not enough) — wrapped in a Surface so it is opaque + readable over whatever is behind. At 0
        // agents there are no AGENT windows to cover (only the tiled tool windows), so it can never occlude agent
        // content and is strictly visible + clickable. Self-clearing (agentsEmpty=false at ≥1 agent). Copy is verbatim
        // CYP-228 ("noch keine Agenten", carries "creating ≠ running"); the CTA routes into the existing openAdd flow,
        // operator-gated with an honest reason (no dead CTA), exactly like the CYP-228 add button.
        if (agentsEmpty) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    // Above the window stack (window zIndex = list index, small); below the FIT toolbar (MAX).
                    .zIndex(Float.MAX_VALUE / 2f)
                    .testTag(WindowTestTags.EMPTY),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(Res.string.agent_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        stringResource(Res.string.agent_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onAddFirstAgent,
                        enabled = canAddAgent,
                        modifier = Modifier.testTag(WindowTestTags.EMPTY_ADD_BTN),
                    ) {
                        Text(stringResource(Res.string.agent_add))
                    }
                    // Non-operator: disabled CTA + the honest "why" (reused gate hint) — no dead end (§D5).
                    if (!canAddAgent) {
                        TonedHint(
                            stringResource(Res.string.workspace_operator_only),
                            HintTone.GATED,
                            WindowTestTags.EMPTY_GATE_HINT,
                        )
                    }
                }
            }
        }

        // "Fit windows" affordance (CYP-26 §2.3): a user-triggered one-shot re-tile that heals
        // off-host/overlapping states. Drawn above the windows; never an automatic re-layout.
        val fitDescription = stringResource(Res.string.a11y_window_fit)
        TextButton(
            onClick = onFit,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .zIndex(Float.MAX_VALUE)
                .testTag(WindowTestTags.FIT)
                .semantics { contentDescription = fitDescription },
        ) {
            Text(stringResource(Res.string.window_fit_action))
        }
    }
}

/** Beyond this many pages the dot indicator is replaced by a compact "N / M" counter (CYP-54 §4). */
private const val PAGER_DOT_THRESHOLD = 6

/** Min touch-target for the indicator dots + prev/next affordances (WCAG 2.5.8 AAA = 44dp; 48dp here). */
private val PAGER_TOUCH_TARGET = 48.dp

/**
 * The phone-pager (CYP-50/S10): shows exactly **one** window content at a time as a snap
 * [HorizontalPager] page. Pages follow [WindowManagerState.windowOrder] — the **stable** registration
 * order, decoupled from z-order — so giving a window focus never re-sorts the pages (CYP-54 §2).
 *
 * The shared anchor [WindowManagerState.focusedId] keeps "which window is active" in lock-step: the
 * settled page drives focus, and an external focus change (deep-link / explicit "bring to front")
 * scrolls the pager (CYP-54 §3/§5). One window → a single static page with no indicator chrome; zero
 * windows → an honest empty state (CYP-54 §6).
 */
@Composable
private fun PhonePager(
    state: WindowManagerState,
    badgeFor: (String) -> WindowBadge?,
    windowContent: @Composable (WindowState) -> Unit,
) {
    val pages = state.orderedWindows
    if (pages.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().testTagA11y(PhonePagerTags.EMPTY),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(Res.string.pager_empty), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    val initialPage = pages.indexOfFirst { it.id == state.focusedId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage) { state.orderedWindows.size }
    val scope = rememberCoroutineScope()

    // Anchor sync — the settled page becomes the focused window (CYP-54 §3). Reads orderedWindows
    // fresh per emission so it never captures a stale page list.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { idx ->
            state.orderedWindows.getOrNull(idx)?.let { state.focus(it.id) }
        }
    }
    // External focus change (deep-link / explicit raise) → scroll to that page (CYP-54 §5). The guard
    // makes the settle→focus→here path a no-op, so there is no feedback loop.
    LaunchedEffect(state.focusedId) {
        val target = state.orderedWindows.indexOfFirst { it.id == state.focusedId }
        if (target >= 0 && target != pagerState.currentPage) pagerState.animateScrollToPage(target)
    }

    val currentIndex = pagerState.currentPage.coerceIn(0, pages.lastIndex)

    Column(modifier = Modifier.fillMaxSize()) {
        // Slim header — only one window is visible, so it carries that page's title (CYP-54 §4).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .testTag(PhonePagerTags.HEADER)
                .semantics { heading() },
        ) {
            Text(
                text = pages[currentIndex].title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag(PhonePagerTags.HEADER_TITLE),
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth().testTagA11y(PhonePagerTags.PAGER),
        ) { index ->
            val window = pages[index]
            // a11y: each page announces position + window name ("Seite 2 von 5: Frontend") — never
            // just "page 2" (CYP-54 §4); the true page count never includes absent windows.
            val pageDesc = stringResource(
                Res.string.a11y_pager_page, (index + 1).toString(), pages.size.toString(), window.title,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(PhonePagerTags.page(window.id))
                    .semantics { contentDescription = pageDesc },
            ) {
                // Reuse window.<id>.content so a window's body is addressable identically to the canvas.
                Box(modifier = Modifier.fillMaxSize().testTagA11y(WindowTestTags.content(window.id))) {
                    windowContent(window)
                }
            }
        }

        // Indicator only when there is more than one page — a lone page advertises no extra pages
        // (CYP-54 §6 disclosure honesty).
        if (pages.size > 1) {
            PagerIndicator(
                pages = pages,
                currentIndex = currentIndex,
                badgeFor = badgeFor,
                onSelect = { idx -> scope.launch { pagerState.animateScrollToPage(idx) } },
            )
        }
    }
}

/**
 * Page indicator + navigation (CYP-54 §4). ≤ [PAGER_DOT_THRESHOLD] pages → tappable dots whose active
 * state is carried by shape/size (a child `…active` marker), not colour alone (WCAG 1.4.1); beyond
 * that → a compact "N / M" counter. Prev/next affordances flank it for discoverability and a11y.
 */
@Composable
private fun PagerIndicator(
    pages: List<WindowState>,
    currentIndex: Int,
    badgeFor: (String) -> WindowBadge?,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTagA11y(PhonePagerTags.INDICATOR)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // RTL: the chevron glyphs are direction-bearing, so mirror them with the layout (the Row itself
        // already mirrors child order). graphicsLayer flip keeps us dependency-free (the project ships no
        // material-icons artifact) while matching AutoMirrored behaviour for the two arrows (CYP-57 #1).
        val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val chevronMirror = Modifier.graphicsLayer { scaleX = if (isRtl) -1f else 1f }

        val prevDesc = stringResource(Res.string.pager_prev)
        Box(
            modifier = Modifier
                .size(PAGER_TOUCH_TARGET)
                .testTag(PhonePagerTags.PREV)
                .semantics { contentDescription = prevDesc; role = Role.Button }
                .clickable(enabled = currentIndex > 0) { onSelect(currentIndex - 1) },
            contentAlignment = Alignment.Center,
        ) {
            Text("‹", style = MaterialTheme.typography.titleMedium, modifier = chevronMirror)
        }

        if (pages.size <= PAGER_DOT_THRESHOLD) {
            pages.forEachIndexed { idx, window ->
                val active = idx == currentIndex
                val dotDesc = stringResource(Res.string.a11y_pager_dot, (idx + 1).toString(), window.title)
                Box(
                    modifier = Modifier
                        .size(PAGER_TOUCH_TARGET)
                        .testTag(PhonePagerTags.dot(window.id))
                        .semantics { contentDescription = dotDesc; role = Role.Button }
                        .clickable { onSelect(idx) },
                    contentAlignment = Alignment.Center,
                ) {
                    // Active = larger + filled; inactive = smaller + dimmed. Shape/size carry the
                    // meaning; the `…active` marker node lets QA assert the active page non-visually.
                    Box(
                        modifier = Modifier
                            .size(if (active) 10.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                            .then(
                                if (active) Modifier.testTag(PhonePagerTags.dotActive(window.id)) else Modifier,
                            ),
                    )
                    // CYP-55: per-page activity badge on the dot for a NON-active page (the active page
                    // is gated by the shell). Reuses the existing phonePager.page.<id>.badge slot
                    // (CYP-54 §6). Only the dot mode (≤ threshold) has a per-page slot; counter mode is
                    // a documented known limitation (WINDOW-BADGES §8) — no lying badge there.
                    val dotBadge = badgeFor(window.id)
                    if (dotBadge != null) {
                        WindowBadgeView(
                            windowId = window.id,
                            windowTitle = window.title,
                            badge = dotBadge,
                            modifier = Modifier.align(Alignment.TopEnd),
                            containerTag = PhonePagerTags.badge(window.id),
                        )
                    }
                }
            }
        } else {
            Text(
                text = stringResource(
                    Res.string.pager_page_position, (currentIndex + 1).toString(), pages.size.toString(),
                ),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .testTag(PhonePagerTags.INDICATOR_POSITION),
            )
        }

        val nextDesc = stringResource(Res.string.pager_next)
        Box(
            modifier = Modifier
                .size(PAGER_TOUCH_TARGET)
                .testTag(PhonePagerTags.NEXT)
                .semantics { contentDescription = nextDesc; role = Role.Button }
                .clickable(enabled = currentIndex < pages.lastIndex) { onSelect(currentIndex + 1) },
            contentAlignment = Alignment.Center,
        ) {
            Text("›", style = MaterialTheme.typography.titleMedium, modifier = chevronMirror)
        }
    }
}

/**
 * A single floating window: a positioned, sized surface with a draggable title bar, a content slot
 * and a bottom-end resize grip. All geometry callbacks report deltas in dp.
 *
 * @param zOrder stacking order; higher draws on top. Set from the host's list index.
 * @param onFocus invoked when the window is pressed, keyboard-focused, or a drag/resize starts.
 * @param onMove drag/keyboard delta to move the window, in dp.
 * @param onResize drag/keyboard delta to resize the window, in dp.
 * @param badge optional activity badge (CYP-55) shown at the end of the title bar; `null` → none.
 */
@Composable
fun FloatingWindow(
    window: WindowState,
    isFocused: Boolean,
    zOrder: Float,
    onFocus: () -> Unit,
    onMove: (dx: Float, dy: Float) -> Unit,
    onResize: (dWidth: Float, dHeight: Float) -> Unit,
    /** CYP-241: titlebar double-click → Expand+Center / Restore toggle; default no-op (tests/callers not wiring it). */
    onToggleExpand: () -> Unit = {},
    /** CYP-241: this window is Expanded (a Restore anchor exists) → titlebar `stateDescription` = "enlarged & centered". */
    isExpanded: Boolean = false,
    badge: WindowBadge? = null,
    /** CYP-316: this window's live context-token count; `null` → no number shown (unknown ≠ 0, §8-3). */
    contextTokens: Int? = null,
    /** CYP-324: this window's agent is busy (a turn in flight) → a `*` in the title bar; `false` → nothing (unknown ≠ busy). */
    busy: Boolean = false,
    /**
     * CYP-656 — this window's live-feed connection status, or **`null` = "no live feed"** (a system window like
     * Settings/Comm, or an unwired host). **REQUIRED (no default) on purpose:** this is the render boundary where the
     * busy-`*`/token-count freshness markers live, so the compiler guarantees every caller states the freshness
     * explicitly. "No info" is an explicit `null`, NEVER a silently-assumed [ConnectionStatus.LIVE] — that fail-open
     * default is exactly the bug class CYP-656 closes. Consumed here: the busy-`*` is suppressed on a non-LIVE feed
     * (a stale discrete-state claim, like the CYP-573 dot); the token count's grey-on-non-LIVE lands in a follow-up
     * commit on this ticket.
     */
    connection: ConnectionStatus?,
    /** CYP-354 (client mirror): this window's terminal-control event; `null`/MEDIATED-state → NO marker (absent ==
     *  MEDIATED, the default). Only a non-MEDIATED state shows the read-only §5.1 mode marker. CYP-381: carries
     *  holder-identity ([AgentTerminalControlEvent.heldBy]) + since so the marker can surface who + since when. */
    control: AgentTerminalControlEvent? = null,
    /** CYP-211: the agent's derived titlebar colours; `null` → the default M3 primary/surfaceVariant theming
     *  (system windows). Focused = full colour; unfocused = dimmed toward the surface (elevation still carries focus). */
    titleBarColors: TitleBarColors? = null,
    titleBarLeading: (@Composable () -> Unit)? = null,
    /** CYP-211: opens this window's settings panel; `null` → no ⋮ button (e.g. system windows). */
    onSettings: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // CYP-241: honest state copy — "enlarged & centered" vs "normal size", NOT "everything visible" (§7). Reflects
    // the Restore-anchor presence, so dragging an expanded window away (anchor cleared) honestly falls to "normal".
    val expandStateDesc = stringResource(if (isExpanded) Res.string.window_state_expanded else Res.string.window_state_normal)
    // CYP-245/CYP-248: a11y discoverability of the Enter (toggle) + Escape (restore-only) window keys. The Escape
    // hint is appended ONLY while expanded — Escape is a no-op in normal state, so it is not advertised then (§9-10).
    val expandKeyHint = stringResource(Res.string.a11y_window_expand_key_hint)
    val restoreKeyHint = stringResource(Res.string.a11y_window_restore_key_hint)
    Box(
        modifier = Modifier
            // Position via graphicsLayer translation + zIndex through the same layer so reordering on
            // focus does not require re-laying-out siblings.
            .graphicsLayer {
                translationX = window.x.dp.toPx()
                translationY = window.y.dp.toPx()
            }
            .zIndex(zOrder)
            .size(window.width.dp, window.height.dp)
            .testTag(WindowTestTags.window(window.id))
            // Screenreader: expose the window as a navigable heading (WCAG 1.3.1/4.1.2). testTag
            // stays separate for tests.
            .semantics {
                heading()
                // CYP-245/248: append the keyboard-key hints to the focusable root's a11y description so a keyboard
                // user learns the Enter/Escape affordances (Escape only while it actually acts — expanded).
                contentDescription = buildString {
                    append("Agentenfenster ${window.title}. ")
                    append(expandKeyHint)
                    if (isExpanded) append(". $restoreKeyHint")
                }
                // CYP-241: Expand/Restore state (anchor presence) exposed for a11y + QA on the window root node.
                stateDescription = expandStateDesc
            }
            // Keyboard operation (WCAG 2.1.1): Tab focuses the window; arrows move it, Shift+arrows
            // resize it. Focusing also raises it. Move/resize go through the same clamped callbacks.
            .onFocusChanged { if (it.isFocused) onFocus() }
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val resize = event.isShiftPressed
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (resize) onResize(-KEYBOARD_RESIZE_STEP, 0f) else onMove(-KEYBOARD_MOVE_STEP, 0f)
                        true
                    }
                    Key.DirectionRight -> {
                        if (resize) onResize(KEYBOARD_RESIZE_STEP, 0f) else onMove(KEYBOARD_MOVE_STEP, 0f)
                        true
                    }
                    Key.DirectionUp -> {
                        if (resize) onResize(0f, -KEYBOARD_RESIZE_STEP) else onMove(0f, -KEYBOARD_MOVE_STEP)
                        true
                    }
                    Key.DirectionDown -> {
                        if (resize) onResize(0f, KEYBOARD_RESIZE_STEP) else onMove(0f, KEYBOARD_MOVE_STEP)
                        true
                    }
                    // CYP-245: Enter toggles Expand+Center ↔ Restore on the focused window — the keyboard equal of
                    // the CYP-241 titlebar double-tap (same onToggleExpand → state.toggleExpand → identical geometry
                    // + stateDescription flip, incl. "no dead key" after a manual move). Fires only when the window
                    // ROOT holds focus; a focused child input keeps its own Enter (e.g. the composer "send"), exactly
                    // as the arrow keys move the cursor inside a text field, not the window.
                    Key.Enter -> {
                        onToggleExpand()
                        true
                    }
                    // CYP-248: Escape = Restore-only. Gated on isExpanded so it can NEVER expand (when expanded,
                    // toggleExpand == Restore). Not expanded (no / move-invalidated anchor) → a NON-consuming no-op
                    // (false) so Escape bubbles free for a dialog/menu/popup close (precedence, spec §2b/D7: a modal
                    // catches focus structurally, so the root never even sees Escape while one is open).
                    Key.Escape -> {
                        if (isExpanded) {
                            onToggleExpand()
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
            // Pressing anywhere on the window raises it. Initial pass + no consume so child drag
            // handlers still receive the gesture; onFocus is idempotent for the already-front window.
            .pointerInput(window.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.changedToDownIgnoreConsumed() }) onFocus()
                    }
                }
            },
    ) {
        // CYP-211 §6 — titlebar theming from the agent's derived colours. Unfocused DIMS the colour toward the
        // surface, but elevation (below) is the non-colour focus signal (WCAG 1.4.1). `null` → default M3 theming.
        val surfaceColor = MaterialTheme.colorScheme.surface
        val barBg = titleBarColors?.let { if (isFocused) it.background else lerp(it.background, surfaceColor, 0.45f) }
            ?: if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        // UX-QA [Low]①: use the FULL derived onColor when unfocused too (no alpha reduction) — it stays
        // contrast-strong; the bg dim + elevation already carry "unfocused" (a 0.75-alpha could drop below 4.5:1).
        val barContent = titleBarColors?.content
            ?: if (isFocused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        val borderColor = titleBarColors?.let { if (isFocused) it.border else lerp(it.border, surfaceColor, 0.45f) }
        Surface(
            modifier = Modifier.fillMaxSize()
                .then(if (borderColor != null) Modifier.border(BorderStroke(1.5.dp, borderColor), RoundedCornerShape(8.dp)) else Modifier),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = if (isFocused) 6.dp else 1.dp,
            shadowElevation = if (isFocused) 12.dp else 2.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Title bar — drag to move.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(barBg)
                        .testTag(WindowTestTags.titleBar(window.id))
                        .semantics { contentDescription = "Titelleiste ${window.title}, mit Pfeiltasten verschieben" }
                        // CYP-241 §3: a SEPARATE tap detector for the double-click (Expand+Center / Restore toggle),
                        // NEXT TO the drag one — not instead. A stationary double-tap fires onDoubleTap; a moving
                        // gesture becomes a drag (detectTapGestures discards the tap past touch-slop) → no conflict.
                        .pointerInput(window.id) {
                            detectTapGestures(onDoubleTap = { onToggleExpand() })
                        }
                        .pointerInput(window.id) {
                            detectDragGestures(
                                onDragStart = { onFocus() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onMove(dragAmount.x.toDp().value, dragAmount.y.toDp().value)
                                },
                            )
                        },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // CYP-216 §5.1: the leading inverted-disc avatar (host-injected), left of the title.
                        titleBarLeading?.let { leading ->
                            Box(modifier = Modifier.padding(end = 8.dp)) { leading() }
                        }
                        Text(
                            text = window.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                            color = barContent,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // CYP-354 §5.1 (client mirror): the read-only terminal-control mode marker — LEADS the status
                        // cluster (after the title, BEFORE busy `*`/token). Rendered ONLY for a non-MEDIATED state
                        // (absent == MEDIATED = the default, shown without chrome — the CYP-324 busy pattern). The
                        // form (◉/→/←/∅) + the label carry the meaning (WCAG 1.4.1); colour only reinforces (WARN-amber
                        // for INTERACTIVE/CONTEXT_LOST, neutral onSurfaceVariant for the transients — never green). The
                        // meaning-bearing label uses `barContent` (AA on the themed title-bar bg, like busy/token); the
                        // glyph carries the severity colour. Content-free (state only — never keystrokes/output).
                        control?.let { ev ->
                            val marker: Triple<String, String, Color>? = when (ev.state) {
                                TerminalControlState.MEDIATED -> null
                                TerminalControlState.INTERACTIVE ->
                                    Triple("◉", stringResource(Res.string.terminal_ctl_interactive), severityColor(Severity.WARN))
                                TerminalControlState.HANDING_OVER ->
                                    Triple("→", stringResource(Res.string.terminal_ctl_handing_over), MaterialTheme.colorScheme.onSurfaceVariant)
                                TerminalControlState.HANDING_BACK ->
                                    Triple("←", stringResource(Res.string.terminal_ctl_handing_back), MaterialTheme.colorScheme.onSurfaceVariant)
                                TerminalControlState.CONTEXT_LOST ->
                                    Triple("∅", stringResource(Res.string.terminal_ctl_context_lost), severityColor(Severity.WARN))
                            }
                            marker?.let { (glyph, label, glyphColor) ->
                                // CYP-381 §5: holder-identity + since from the SAME AgentTerminalControlEvent (also the
                                // CYP-355 response echo). Rendered as the spec "@{heldBy} · seit {HH:MM}" chip AND the
                                // merged a11y label (meaning-bearing, WCAG 1.4.1). since = local HH:MM (formatLocalHhMm).
                                val holder = ev.heldBy
                                val sinceHhMm = ev.since?.let { formatLocalHhMm(it) }
                                val holderChip = holder?.let { h -> // "@alice · seit 14:03" (or "@alice" if no since)
                                    if (sinceHhMm != null) stringResource(Res.string.terminal_held_by, "@$h", sinceHhMm) else "@$h"
                                }
                                val modeCd = when {
                                    holder != null && sinceHhMm != null ->
                                        stringResource(Res.string.a11y_terminal_held_by, holder, sinceHhMm)
                                    holder != null -> stringResource(Res.string.a11y_terminal_ctl_held, label, holder)
                                    else -> stringResource(Res.string.a11y_terminal_ctl, label)
                                }
                                Row(
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .testTag(WindowTestTags.mode(window.id))
                                        // One merged SR node; glyph + label + holder chip stay visual.
                                        .semantics(mergeDescendants = true) { contentDescription = modeCd },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(glyph, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = glyphColor)
                                    Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium, color = barContent)
                                    if (holderChip != null) {
                                        Text(
                                            holderChip,
                                            maxLines = 1,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = barContent,
                                            modifier = Modifier.testTag(WindowTestTags.modeHolder(window.id)),
                                        )
                                    }
                                }
                            }
                        }
                        // CYP-324: the live busy marker — a `*` while the agent has a turn in flight (`/ws/busy-state`),
                        // in `barContent` (full, no alpha → AA on `barBg`). Sits AFTER the title (which ellipsizes first)
                        // and coexists with the token count. Rendered ONLY when busy (Z1); `false`/no-event (idle / pre-
                        // first-turn / unknown) shows NOTHING — never a stale `*` (unknown ≠ busy). The a11y label carries
                        // the meaning (never colour/glyph alone).
                        //
                        // CYP-656: ALSO gate on a LIVE feed. The busy flag is held last-known across a WS drop (its
                        // socket doesn't reset it), so across a real server/hub restart a `*` could keep asserting
                        // "running a turn" for an already-stopped agent through the reconnect gap. `*` is a DISCRETE
                        // state claim — it lies, exactly like the CYP-573 dot did. Fail closed: only assert busy while
                        // the feed is [ConnectionStatus.LIVE]; on a non-LIVE (or absent = `null`) feed suppress it —
                        // "unknown ≠ busy" extended to "not-confirmably-fresh ≠ busy" (the reconnecting chip carries the
                        // freshness signal in the body). A stale `*` is never shown.
                        if (busy && connection == ConnectionStatus.LIVE) {
                            val busyCd = stringResource(Res.string.a11y_agent_busy, window.title)
                            Text(
                                text = "*",
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = barContent,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .testTag(WindowTestTags.busy(window.id))
                                    .semantics { contentDescription = busyCd },
                            )
                        }
                        // CYP-316: the live context-token count — compact, monospace (stable digit width → a live
                        // tick causes NO reflow jitter, §8-6), `barContent` (full, no alpha → AA on `barBg`). Sits
                        // AFTER the title (which ellipsizes first) and BEFORE the badge → `Avatar · Title · [137k] ·
                        // Badge · ⋮`. Rendered ONLY when non-null (Z1); `null` (Connector-B / pre-first-turn / unknown)
                        // shows NOTHING — never "0" (the §8-3 honesty core: null ≠ 0). The a11y label carries meaning.
                        contextTokens?.let { n ->
                            val compact = formatCompactTokens(n)
                            val tokensCd = stringResource(Res.string.a11y_agent_context_tokens, compact)
                            Text(
                                text = compact,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = barContent,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .testTag(WindowTestTags.contextTokens(window.id))
                                    .semantics { contentDescription = tokensCd },
                            )
                        }
                        // CYP-55 activity badge at the title end (fail-closed: only when present).
                        if (badge != null) {
                            WindowBadgeView(
                                windowId = window.id,
                                windowTitle = window.title,
                                badge = badge,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        // CYP-211: the ⋮ settings button — agent windows only (onSettings null → absent). Themed
                        // onColor so it stays legible on any agent colour; text glyph + a11y (never colour-only).
                        onSettings?.let { open ->
                            val settingsCd = stringResource(Res.string.a11y_agent_settings_open, window.title)
                            TextButton(
                                onClick = open,
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .testTag(WindowTestTags.settings(window.id))
                                    .semantics { contentDescription = settingsCd },
                            ) {
                                Text("⋮", style = MaterialTheme.typography.titleMedium, color = barContent)
                            }
                        }
                    }
                }

                // Content slot — arbitrary composable supplied by the host.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTagA11y(WindowTestTags.content(window.id)),
                ) {
                    content()
                }
            }
        }

        // Resize grip — drag from the bottom-end corner. The hit area is RESIZE_HIT_SLOP (44dp) for a
        // comfortable touch target (WCAG 2.5.8); the visible grip is RESIZE_HANDLE_SIZE (24dp).
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(RESIZE_HIT_SLOP.dp)
                .testTag(WindowTestTags.resizeHandle(window.id))
                .semantics { contentDescription = "Größe ändern, ${window.title}" }
                .pointerInput(window.id, isRtl) {
                    detectDragGestures(
                        onDragStart = { onFocus() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            // RTL: the grip sits at the visual start, so mirror the horizontal delta.
                            val dWidth = WindowReducer.resizeDeltaForLayout(dragAmount.x.toDp().value, isRtl)
                            onResize(dWidth, dragAmount.y.toDp().value)
                        },
                    )
                },
            contentAlignment = Alignment.BottomEnd,
        ) {
            Box(
                modifier = Modifier
                    .size(RESIZE_HANDLE_SIZE.dp)
                    .clip(RoundedCornerShape(topStart = 8.dp))
                    .background(MaterialTheme.colorScheme.secondary),
            )
        }
    }
}
