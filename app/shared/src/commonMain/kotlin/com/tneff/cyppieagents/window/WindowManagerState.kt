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

/** Smallest width a window may be resized to, in dp. */
const val MIN_WINDOW_WIDTH: Float = 160f

/** Smallest height a window may be resized to, in dp. */
const val MIN_WINDOW_HEIGHT: Float = 120f

/**
 * How much of a window must remain inside the host on every edge, in dp, so it can never be dragged
 * completely out of the visible area.
 */
const val MIN_VISIBLE_WINDOW: Float = 48f

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
     * Grows/shrinks only the window with [id] by ([dWidth], [dHeight]) dp, clamped so it never
     * collapses below ([minWidth], [minHeight]).
     */
    fun resizeBy(
        windows: List<WindowState>,
        id: String,
        dWidth: Float,
        dHeight: Float,
        minWidth: Float = MIN_WINDOW_WIDTH,
        minHeight: Float = MIN_WINDOW_HEIGHT,
    ): List<WindowState> = windows.map {
        if (it.id == id) {
            it.copy(
                width = maxOf(minWidth, it.width + dWidth),
                height = maxOf(minHeight, it.height + dHeight),
            )
        } else {
            it
        }
    }

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
     * Produces an initial side-by-side grid layout so that on first load windows are tiled rather
     * than stacked. [items] is a list of `id to title`; positions/sizes are derived from the host
     * size ([hostWidth] x [hostHeight] dp) and never go below the minimum window size.
     */
    fun tile(
        items: List<Pair<String, String>>,
        hostWidth: Float,
        hostHeight: Float,
        gap: Float = 16f,
    ): List<WindowState> {
        if (items.isEmpty()) return emptyList()
        val count = items.size
        val columns = ceil(sqrt(count.toDouble())).toInt().coerceAtLeast(1)
        val rows = ceil(count.toDouble() / columns).toInt().coerceAtLeast(1)

        // Fall back to a layout that still fits the minimum tile size if the host hasn't been
        // measured yet (host size 0) or is smaller than the grid needs.
        val usableWidth = hostWidth.coerceAtLeast(columns * (MIN_WINDOW_WIDTH + gap) + gap)
        val usableHeight = rows.let { r -> hostHeight.coerceAtLeast(r * (MIN_WINDOW_HEIGHT + gap) + gap) }
        val cellWidth = (usableWidth - gap * (columns + 1)) / columns
        val cellHeight = (usableHeight - gap * (rows + 1)) / rows

        return items.mapIndexed { i, (id, title) ->
            val col = i % columns
            val row = i / columns
            WindowState(
                id = id,
                title = title,
                x = gap + col * (cellWidth + gap),
                y = gap + row * (cellHeight + gap),
                width = cellWidth.coerceAtLeast(MIN_WINDOW_WIDTH),
                height = cellHeight.coerceAtLeast(MIN_WINDOW_HEIGHT),
            )
        }
    }
}

/**
 * Observable holder driving the window manager UI. Wraps the window list in Compose snapshot state
 * and routes every mutation through [WindowReducer], so the UI re-renders while the logic stays pure.
 */
class WindowManagerState(initial: List<WindowState>) {

    var windows: List<WindowState> by mutableStateOf(initial)
        private set

    // Last measured host size (dp). Plain fields — they only feed clamp math, not rendering.
    private var hostWidth: Float = 0f
    private var hostHeight: Float = 0f

    /** Id of the currently focused (top-most) window, or `null` when there are no windows. */
    val focusedId: String?
        get() = windows.lastOrNull()?.id

    /** Records the current host size and re-clamps every window so none is left stranded off-screen. */
    fun updateHostSize(width: Float, height: Float) {
        hostWidth = width
        hostHeight = height
        windows = windows.map { WindowReducer.clampToBounds(it, width, height) }
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
        windows = WindowReducer.resizeBy(windows, id, dWidth, dHeight)
    }
}
