package com.tneff.cyppieagents.window

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import com.tneff.cyppieagents.testing.testTagA11y
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_pager_dot
import kmpcyppieagents.app.shared.generated.resources.a11y_pager_page
import kmpcyppieagents.app.shared.generated.resources.pager_empty
import kmpcyppieagents.app.shared.generated.resources.a11y_window_fit
import kmpcyppieagents.app.shared.generated.resources.pager_next
import kmpcyppieagents.app.shared.generated.resources.pager_page_position
import kmpcyppieagents.app.shared.generated.resources.pager_prev
import kmpcyppieagents.app.shared.generated.resources.window_fit_action
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

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
            PhonePager(state = state, badgeFor = badgeFor, windowContent = windowContent)
        } else {
            WindowCanvas(state = state, onFit = onFit, badgeFor = badgeFor, windowContent = windowContent)
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
    windowContent: @Composable (WindowState) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().testTagA11y(WindowTestTags.HOST)) {
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
                    badge = badgeFor(window.id),
                    content = { windowContent(window) },
                )
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
    badge: WindowBadge? = null,
    content: @Composable () -> Unit,
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
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
                contentDescription = "Agentenfenster ${window.title}"
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
        Surface(
            modifier = Modifier.fillMaxSize(),
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
                        .background(
                            if (isFocused) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .testTag(WindowTestTags.titleBar(window.id))
                        .semantics { contentDescription = "Titelleiste ${window.title}, mit Pfeiltasten verschieben" }
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
                        Text(
                            text = window.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isFocused) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // CYP-55 activity badge at the title end (fail-closed: only when present).
                        if (badge != null) {
                            WindowBadgeView(
                                windowId = window.id,
                                windowTitle = window.title,
                                badge = badge,
                                modifier = Modifier.padding(start = 8.dp),
                            )
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
