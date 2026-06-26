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

    /** Id of the currently focused (top-most) window, or `null` when there are no windows. */
    val focusedId: String?
        get() = windows.lastOrNull()?.id

    fun focus(id: String) {
        windows = WindowReducer.bringToFront(windows, id)
    }

    fun moveBy(id: String, dx: Float, dy: Float) {
        windows = WindowReducer.moveBy(windows, id, dx, dy)
    }

    fun resizeBy(id: String, dWidth: Float, dHeight: Float) {
        windows = WindowReducer.resizeBy(windows, id, dWidth, dHeight)
    }
}
