package com.tneff.cyppieagents.window

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Immutable description of a single floating window's identity and geometry.
 *
 * Geometry is expressed in density-independent pixels (dp) as plain [Float]s on purpose: it keeps
 * the window-management logic free of Compose UI types so [WindowReducer] is unit-testable on every
 * target without a Compose runtime or a device.
 */
data class WindowState(
    val id: String,
    val title: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/** Smallest width a window may be resized to, in dp. Hard floor for non-content windows (ACL/Event-Log). */
const val MIN_WINDOW_WIDTH: Float = 160f

/**
 * Min width for tiled **content** windows (Agent/Comm) so the composer + send button stay usable
 * (CYP-26 §2.2; `COMPOSER_MIN_WIDTH` + send + padding). Does **not** replace the 160 dp floor for other
 * window types. Applied by [WindowReducer.tile] only when room allows — "fully visible" wins over it.
 */
const val TILED_CONTENT_WINDOW_MIN_WIDTH: Float = 320f

/**
 * Content guarantee of the composer input field, in dp (CYP-26 §2.2). Below a window width that can't
 * fit input + send label, the send control degrades to an icon button rather than truncating.
 */
const val COMPOSER_MIN_WIDTH: Float = 280f

/** Smallest height a window may be resized to, in dp. */
const val MIN_WINDOW_HEIGHT: Float = 120f

/**
 * How much of a window must remain inside the host on every edge, in dp, so it can never be dragged
 * completely out of the visible area.
 */
const val MIN_VISIBLE_WINDOW: Float = 48f

/** Visible size of the resize grip, in dp. Meets the WCAG 2.5.8 minimum target size (24 dp). */
const val RESIZE_HANDLE_SIZE: Float = 24f

/** Touch/hit area around the resize grip, in dp — a comfortable target well above the 24 dp floor. */
const val RESIZE_HIT_SLOP: Float = 44f

/** Step a window moves per arrow-key press, in dp (keyboard operation, WCAG 2.1.1). */
const val KEYBOARD_MOVE_STEP: Float = 16f

/** Step a window resizes per Shift+arrow-key press, in dp (keyboard operation, WCAG 2.1.1). */
const val KEYBOARD_RESIZE_STEP: Float = 16f

/**
 * Pure, side-effect-free transformations over the window list.
 *
 * Invariant: list order encodes the z-order — index `0` is bottom-most and the **last** element is
 * the top-most (focused) window. Giving a window focus therefore moves it to the end of the list.
 */
object WindowReducer {

    /** Moves the window with [id] to the front (end) of the stack. No-op if absent or already front. */
    fun bringToFront(windows: List<WindowState>, id: String): List<WindowState> {
        val index = windows.indexOfFirst { it.id == id }
        if (index < 0 || index == windows.lastIndex) return windows
        return windows.toMutableList().apply { add(removeAt(index)) }
    }

    /** Translates only the window with [id] by ([dx], [dy]) dp; all others are untouched. */
    fun moveBy(windows: List<WindowState>, id: String, dx: Float, dy: Float): List<WindowState> =
        windows.map { if (it.id == id) it.copy(x = it.x + dx, y = it.y + dy) else it }

    /**
     * Grows/shrinks only the window with [id] by ([dWidth], [dHeight]) dp, clamped into
     * `[[minWidth], [maxWidth]]` x `[[minHeight], [maxHeight]]`. By default the upper bound is
     * unbounded; the holder passes the host-derived maxima so a window cannot grow past the host
     * (CYP-16 F1).
     */
    fun resizeBy(
        windows: List<WindowState>,
        id: String,
        dWidth: Float,
        dHeight: Float,
        minWidth: Float = MIN_WINDOW_WIDTH,
        minHeight: Float = MIN_WINDOW_HEIGHT,
        maxWidth: Float = Float.POSITIVE_INFINITY,
        maxHeight: Float = Float.POSITIVE_INFINITY,
    ): List<WindowState> = windows.map {
        if (it.id == id) {
            it.copy(
                width = (it.width + dWidth).coerceIn(minWidth, maxOf(minWidth, maxWidth)),
                height = (it.height + dHeight).coerceIn(minHeight, maxOf(minHeight, maxHeight)),
            )
        } else {
            it
        }
    }

    /**
     * Mirrors the horizontal resize delta for right-to-left layouts. The resize grip sits at the
     * visual bottom-*end* corner, which is bottom-left in RTL, so a positive on-screen drag must
     * shrink/grow with the opposite sign (CYP-16 F4).
     */
    fun resizeDeltaForLayout(dragX: Float, isRtl: Boolean): Float = if (isRtl) -dragX else dragX

    /**
     * Clamps a window's top-left so at least [keepVisible] dp stays inside the host on every edge,
     * guaranteeing it can never be dragged completely off-screen. The top edge is kept at or below
     * `0` so the (draggable) title bar always stays reachable.
     *
     * Returns the window unchanged when the host has not been measured yet
     * ([hostWidth]/[hostHeight] `<= 0`).
     */
    fun clampToBounds(
        window: WindowState,
        hostWidth: Float,
        hostHeight: Float,
        keepVisible: Float = MIN_VISIBLE_WINDOW,
    ): WindowState {
        if (hostWidth <= 0f || hostHeight <= 0f) return window
        val minX = keepVisible - window.width
        val maxX = (hostWidth - keepVisible).coerceAtLeast(minX)
        val minY = 0f
        val maxY = (hostHeight - keepVisible).coerceAtLeast(minY)
        return window.copy(
            x = window.x.coerceIn(minX, maxX),
            y = window.y.coerceIn(minY, maxY),
        )
    }

    /**
     * Shrinks a window so it is never larger than the host (but never below the minimum). Used when
     * the host shrinks (resize/rotation/split-screen) so an oversized window snaps back into view
     * (CYP-16 F1/F6). No-op until the host is measured.
     */
    fun clampSizeToBounds(
        window: WindowState,
        hostWidth: Float,
        hostHeight: Float,
        minWidth: Float = MIN_WINDOW_WIDTH,
        minHeight: Float = MIN_WINDOW_HEIGHT,
    ): WindowState {
        if (hostWidth <= 0f || hostHeight <= 0f) return window
        return window.copy(
            width = window.width.coerceIn(minWidth, maxOf(minWidth, hostWidth)),
            height = window.height.coerceIn(minHeight, maxOf(minHeight, hostHeight)),
        )
    }

    /**
     * Column cap by **Width** Window-Size-Class (CYP-26 §2.1): Medium (600–839 dp) tiles ≤ 2 columns;
     * Expanded (≥ 840 dp) is uncapped (`sqrt`); below Medium is Compact (pager territory, S10) → a
     * single column. Pure dp thresholds keep [tile] testable without a Compose runtime.
     */
    fun columnCapForWidth(hostWidth: Float): Int = when {
        hostWidth >= 840f -> Int.MAX_VALUE
        hostWidth >= 600f -> 2
        else -> 1
    }

    /**
     * Produces the side-by-side default grid (CYP-26). Columns are capped by the Width-Size-Class
     * ([columnCapForWidth]) instead of a naked `sqrt`, so on a Medium host content windows stay wide
     * enough for their composer. The layout is **fully visible**: every window is kept within
     * `[0, host − size]` (§2.1) — distinct from the 48 dp manual-move floor. Content windows
     * ([contentWindowIds] = Agent/Comm) get the wider [TILED_CONTENT_WINDOW_MIN_WIDTH] floor; others
     * keep [MIN_WINDOW_WIDTH]. RTL mirrors the columns to the start (visual right) edge (§6/F10).
     *
     * Should be called only once the host is measured (`hostWidth/Height > 0`); the caller re-tiles
     * once on the first real measurement and on the explicit "fit windows" action (§2.3).
     */
    fun tile(
        items: List<Pair<String, String>>,
        hostWidth: Float,
        hostHeight: Float,
        gap: Float = 16f,
        isRtl: Boolean = false,
        contentWindowIds: Set<String> = emptySet(),
    ): List<WindowState> {
        if (items.isEmpty()) return emptyList()
        val count = items.size
        val columns = minOf(ceil(sqrt(count.toDouble())).toInt(), columnCapForWidth(hostWidth)).coerceAtLeast(1)
        val rows = ceil(count.toDouble() / columns).toInt().coerceAtLeast(1)

        // Fall back to a layout that still fits the minimum tile size if the host hasn't been
        // measured yet (host size 0) or is smaller than the grid needs.
        val usableWidth = hostWidth.coerceAtLeast(columns * (MIN_WINDOW_WIDTH + gap) + gap)
        val usableHeight = hostHeight.coerceAtLeast(rows * (MIN_WINDOW_HEIGHT + gap) + gap)
        val cellWidth = (usableWidth - gap * (columns + 1)) / columns
        val cellHeight = (usableHeight - gap * (rows + 1)) / rows

        return items.mapIndexed { i, (id, title) ->
            val col = i % columns
            val row = i / columns
            val minWidth = if (id in contentWindowIds) TILED_CONTENT_WINDOW_MIN_WIDTH else MIN_WINDOW_WIDTH
            val width = cellWidth.coerceAtLeast(minWidth)
            val height = cellHeight.coerceAtLeast(MIN_WINDOW_HEIGHT)
            val xLtr = gap + col * (cellWidth + gap)
            val rawX = if (isRtl) usableWidth - xLtr - width else xLtr
            WindowState(
                id = id,
                title = title,
                // Fully visible: keep each window within the host (default layout never goes off-host).
                x = rawX.coerceIn(0f, maxOf(0f, hostWidth - width)),
                y = (gap + row * (cellHeight + gap)).coerceIn(0f, maxOf(0f, hostHeight - height)),
                width = width,
                height = height,
            )
        }
    }
}

/**
 * Observable holder driving the window manager UI. Wraps the window list in Compose snapshot state
 * and routes every mutation through [WindowReducer], so the UI re-renders while the logic stays pure.
 */
class WindowManagerState(
    initial: List<WindowState>,
    /** Ids of content windows (Agent/Comm) that get the wider tiled min-width (CYP-26 §2.2). */
    private val contentWindowIds: Set<String> = emptySet(),
) {

    var windows: List<WindowState> by mutableStateOf(initial)
        private set

    /**
     * Stable page/registration order of window ids, captured once from the initial list and **never**
     * reordered by focus. The canvas [windows] list encodes z-order (focus moves to the end); the phone
     * pager (CYP-50/S10) keys its pages off this stable order instead, so giving a window focus never
     * re-sorts the pages (CYP-54 §2). Membership is fixed at construction (windows aren't added/removed
     * at runtime), so this stays in lock-step with [windows].
     */
    val windowOrder: List<String> = initial.map { it.id }

    /**
     * Windows in the stable [windowOrder] (not z-order) — the phone pager's page list. Reads the
     * snapshot-backed [windows] so geometry/title changes still recompose, while the **order** stays
     * fixed regardless of focus.
     */
    val orderedWindows: List<WindowState>
        get() = windowOrder.mapNotNull { id -> windows.firstOrNull { it.id == id } }

    // Last measured host size (dp). Plain fields — they only feed clamp math, not rendering.
    private var hostWidth: Float = 0f
    private var hostHeight: Float = 0f

    /** Id of the currently focused (top-most) window, or `null` when there are no windows. */
    val focusedId: String?
        get() = windows.lastOrNull()?.id

    /**
     * Records the current host size and re-validates every window: first shrinks any that no longer
     * fit, then re-clamps positions so none is left stranded off-screen (CYP-16 F1/F6).
     */
    fun updateHostSize(width: Float, height: Float) {
        hostWidth = width
        hostHeight = height
        windows = windows.map {
            val resized = WindowReducer.clampSizeToBounds(it, width, height)
            WindowReducer.clampToBounds(resized, width, height)
        }
    }

    /**
     * Re-tiles every window into the current host (CYP-26 §2.3): the explicit "fit windows" action and
     * the one-shot re-tile once the host is first measured. Preserves identity (same ids/titles, stable
     * [windowOrder]); only geometry changes. No-op until the host is measured. [isRtl] mirrors columns.
     */
    fun fit(isRtl: Boolean = false) {
        if (hostWidth <= 0f || hostHeight <= 0f) return
        windows = WindowReducer.tile(
            // Tile in the STABLE registration order (not z-order) so "fit" is deterministic (CYP-54 §2).
            items = orderedWindows.map { it.id to it.title },
            hostWidth = hostWidth,
            hostHeight = hostHeight,
            isRtl = isRtl,
            contentWindowIds = contentWindowIds,
        )
    }

    fun focus(id: String) {
        windows = WindowReducer.bringToFront(windows, id)
    }

    fun moveBy(id: String, dx: Float, dy: Float) {
        val moved = WindowReducer.moveBy(windows, id, dx, dy)
        windows = moved.map {
            if (it.id == id) WindowReducer.clampToBounds(it, hostWidth, hostHeight) else it
        }
    }

    fun resizeBy(id: String, dWidth: Float, dHeight: Float) {
        val target = windows.firstOrNull { it.id == id } ?: return
        // Cap growth so the window cannot extend past the host's right/bottom edges (CYP-16 F1).
        val maxWidth = if (hostWidth > 0f) hostWidth - target.x else Float.POSITIVE_INFINITY
        val maxHeight = if (hostHeight > 0f) hostHeight - target.y else Float.POSITIVE_INFINITY
        windows = WindowReducer.resizeBy(
            windows = windows,
            id = id,
            dWidth = dWidth,
            dHeight = dHeight,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
        )
    }
}
