package com.tneff.cyppieagents.window

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.ceil
import kotlin.math.floor
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
 * **Invariant** floor for **content** windows (Agent/Comm), in dp: their fixed chrome, **measured on the
 * rendered composition** at the [TILED_CONTENT_WINDOW_MIN_WIDTH] of 320 dp — title bar 64 + agent header 164
 * + composer 73. Below this the composer, the last unweighted child of the column, cannot be laid out at all;
 * a content window without its input row has stopped being one. No path — placement, resize, clamp, tile,
 * fallback — may ever produce less.
 *
 * The 164 dp header is **width-dependent**: the Start/Stop/Restart `TextButton` labels wrap below ~520 dp of
 * window width and the header Row grows with them (measured 320→164, 480→104, 520→56). `min-window-height-
 * spec.md` §2.1 derives 48 dp from the unwrapped Row, which only holds at >=520 dp. Stop the header from
 * wrapping (icon buttons / overflow, CYP-350) and the header becomes its unwrapped 56 dp, taking this to
 * 64 + 56 + 73 = 193 — every summand still measured; a measured and an arithmetic value must never share a sum.
 */
const val CONTENT_WINDOW_MIN_HEIGHT: Float = 301f

/**
 * Min height for **content** windows (Agent/Comm), the height twin of [TILED_CONTENT_WINDOW_MIN_WIDTH]
 * (CYP-338). [CONTENT_WINDOW_MIN_HEIGHT] of chrome plus 90 dp of transcript — three text lines, the smallest
 * view in which a wrapped answer coexists with a neighbouring row rather than being the whole window
 * (`min-window-height-spec.md` §2.2/§2.3).
 *
 * Rendered, not computed: the chrome summand comes from a measurement of the real composition, not from the
 * Material token arithmetic, because the header wraps at this class's own minimum width (see
 * [CONTENT_WINDOW_MIN_HEIGHT]). **This number falls to 283 once the header stops wrapping (CYP-350)** —
 * `193 + 90`, both summands measured. It is a measured consequence, not a chosen size, so do not round it.
 * `tile` may squeeze a content window *below* this, never below [CONTENT_WINDOW_MIN_HEIGHT] (spec §5). Does
 * **not** replace the 120 dp floor for other window types — they have no composer to lose.
 */
const val TILED_CONTENT_WINDOW_MIN_HEIGHT: Float = 391f

/**
 * How much of a window must remain inside the host on every edge, in dp, so it can never be dragged
 * completely out of the visible area.
 */
const val MIN_VISIBLE_WINDOW: Float = 48f

/**
 * Height of the host **affordance band** reserved at the top of the canvas for the "fit windows"
 * toolbar (CYP-95). [WindowReducer.tile] keeps every tiled window below it, so the `window.host.fit`
 * control (top-end, drawn above the windows) can never overlap a window's title bar / drag corner /
 * agent header — the CYP-73/§2.3 free space is **guaranteed**, not incidental. Comfortably exceeds the
 * `TextButton`'s height so the bounds stay disjoint.
 */
const val HOST_AFFORDANCE_BAND: Float = 56f

/** Visible size of the resize grip, in dp. Meets the WCAG 2.5.8 minimum target size (24 dp). */
const val RESIZE_HANDLE_SIZE: Float = 24f

/** Touch/hit area around the resize grip, in dp — a comfortable target well above the 24 dp floor. */
const val RESIZE_HIT_SLOP: Float = 44f

/** Step a window moves per arrow-key press, in dp (keyboard operation, WCAG 2.1.1). */
const val KEYBOARD_MOVE_STEP: Float = 16f

/** Step a window resizes per Shift+arrow-key press, in dp (keyboard operation, WCAG 2.1.1). */
const val KEYBOARD_RESIZE_STEP: Float = 16f

// --- CYP-241 titlebar double-click Expand+Center (tokens; abstimmbar, live next to the other window constants) ---

/** Preferred expand width for **content** windows (Agent/Comm), dp — capped to the usable viewport. */
const val EXPAND_PREFERRED_CONTENT_W: Float = 640f

/** Preferred expand height for **content** windows, dp — capped to the usable viewport. */
const val EXPAND_PREFERRED_CONTENT_H: Float = 560f

/** Preferred expand width for other windows (e.g. composer), dp — capped to usable, floored to [MIN_WINDOW_WIDTH]. */
const val EXPAND_PREFERRED_DEFAULT_W: Float = 460f

/** Preferred expand height for other windows, dp — capped to usable, floored to [MIN_WINDOW_HEIGHT]. */
const val EXPAND_PREFERRED_DEFAULT_H: Float = 380f

/**
 * Both-sides viewport margin (dp) so Expand is **never** fullscreen/overflow — reads as a large centered window,
 * not fill-max (CYP-95-consistent). `usableW = hostWidth − 2·EXPAND_MARGIN`;
 * `usableH = hostHeight − HOST_AFFORDANCE_BAND − 2·EXPAND_MARGIN`.
 */
const val EXPAND_MARGIN: Float = 24f

/**
 * Pure, side-effect-free transformations over the window list.
 *
 * Invariant: list order encodes the z-order — index `0` is bottom-most and the **last** element is
 * the top-most (focused) window. Giving a window focus therefore moves it to the end of the list.
 */
object WindowReducer {

    /**
     * CYP-338 — the height floor a window may never sink below, by window class. Content windows keep their
     * composer ([CONTENT_WINDOW_MIN_HEIGHT]); everything else keeps the plain [MIN_WINDOW_HEIGHT]. This is the
     * height twin of the `contentWindowIds ? TILED_CONTENT_WINDOW_MIN_WIDTH : MIN_WINDOW_WIDTH` choice, and the
     * single place that decision is made.
     */
    fun invariantMinHeight(isContent: Boolean): Float =
        if (isContent) CONTENT_WINDOW_MIN_HEIGHT else MIN_WINDOW_HEIGHT

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
     * CYP-241 — the Expand+Center target for one window: a per-type **preferred** size, component-wise **capped**
     * to the usable viewport (never fullscreen — [EXPAND_MARGIN] on every edge; never overflow — the final
     * [clampSizeToBounds]/[clampToBounds]), **floored** to the type minimum, and **centered** in the usable area
     * below the [HOST_AFFORDANCE_BAND] (same viewport discipline as [tile], CYP-95). Pure/testable — no Compose.
     * Returns [window] unchanged until the host is measured (`hostWidth`/`hostHeight <= 0`).
     *
     * "Preferred, capped" — NOT wrap-content: an agent window renders an unbounded scrolling stream, so a literal
     * "natural size" is meaningless; the honest promise is "enlarged & centered", not "everything visible" (§0/§1).
     */
    fun expandCentered(
        window: WindowState,
        hostWidth: Float,
        hostHeight: Float,
        isContent: Boolean,
    ): WindowState {
        if (hostWidth <= 0f || hostHeight <= 0f) return window
        val usableW = hostWidth - 2f * EXPAND_MARGIN
        val usableH = hostHeight - HOST_AFFORDANCE_BAND - 2f * EXPAND_MARGIN
        val prefW = if (isContent) EXPAND_PREFERRED_CONTENT_W else EXPAND_PREFERRED_DEFAULT_W
        val prefH = if (isContent) EXPAND_PREFERRED_CONTENT_H else EXPAND_PREFERRED_DEFAULT_H
        val typeMinW = if (isContent) TILED_CONTENT_WINDOW_MIN_WIDTH else MIN_WINDOW_WIDTH
        // CYP-338: the height had MIN_WINDOW_HEIGHT one line below the width's typeMinW — the same asymmetry.
        val typeMinH = if (isContent) TILED_CONTENT_WINDOW_MIN_HEIGHT else MIN_WINDOW_HEIGHT
        val width = minOf(prefW, usableW).coerceAtLeast(typeMinW)
        val height = minOf(prefH, usableH).coerceAtLeast(typeMinH)
        // Window centre = usable-area centre (top band reserved, exactly like tile()).
        val x = (hostWidth - width) / 2f
        val y = HOST_AFFORDANCE_BAND + (hostHeight - HOST_AFFORDANCE_BAND - height) / 2f
        val centered = window.copy(x = x, y = y, width = width, height = height)
        // Defensive: the SAME clamps as tiling → never off-host / oversized on a tiny host (CYP-95).
        return clampToBounds(clampSizeToBounds(centered, hostWidth, hostHeight), hostWidth, hostHeight)
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
        /** Top band reserved for the host fit toolbar (CYP-95); windows tile below it. */
        topBand: Float = HOST_AFFORDANCE_BAND,
    ): List<WindowState> {
        if (items.isEmpty()) return emptyList()
        val count = items.size
        // CYP-95 [Low]: never tile more columns than actually fit side-by-side at the widest applicable
        // min-width — on a narrow Medium host (2 × min > host) fall to a single column instead of
        // placing overlapping "fully-visible" windows. Uses the content floor when any content window is
        // present (its 320 dp dominates), else the 160 dp floor; ignored until the host is measured.
        val widestMinWidth = if (items.any { it.first in contentWindowIds }) TILED_CONTENT_WINDOW_MIN_WIDTH else MIN_WINDOW_WIDTH
        // Non-overlap criterion: N columns need N·min + (N−1)·gap ≤ host (edge-to-edge, no outer margin)
        // → N ≤ (host + gap) / (min + gap). So two 320 dp content windows drop to one column once the
        // host can't hold them without overlap (≈ 656 dp), but a roomier Medium keeps two.
        val columnsThatFit = if (hostWidth > 0f) {
            floor((hostWidth + gap) / (widestMinWidth + gap)).toInt().coerceAtLeast(1)
        } else {
            Int.MAX_VALUE
        }
        val columns = minOf(ceil(sqrt(count.toDouble())).toInt(), columnCapForWidth(hostWidth), columnsThatFit).coerceAtLeast(1)
        val rows = ceil(count.toDouble() / columns).toInt().coerceAtLeast(1)

        // Fall back to a layout that still fits the minimum tile size if the host hasn't been
        // measured yet (host size 0) or is smaller than the grid needs.
        val usableWidth = hostWidth.coerceAtLeast(columns * (MIN_WINDOW_WIDTH + gap) + gap)
        // CYP-95 [Medium]: reserve a top band for the host fit toolbar; windows tile in the remaining
        // height below it, so the top row's chrome never lands under the (top-end) fit control.
        val bandedHeight = hostHeight - topBand
        val usableHeight = bandedHeight.coerceAtLeast(rows * (MIN_WINDOW_HEIGHT + gap) + gap)
        val cellWidth = (usableWidth - gap * (columns + 1)) / columns
        val cellHeight = (usableHeight - gap * (rows + 1)) / rows

        return items.mapIndexed { i, (id, title) ->
            val col = i % columns
            val row = i / columns
            val isContent = id in contentWindowIds
            val minWidth = if (isContent) TILED_CONTENT_WINDOW_MIN_WIDTH else MIN_WINDOW_WIDTH
            val width = cellWidth.coerceAtLeast(minWidth)
            // CYP-338 (spec §5): "fully visible wins" — a crowded grid may squeeze a content window BELOW
            // TILED_CONTENT_WINDOW_MIN_HEIGHT, but never below the composer invariant. Flooring the cell at the
            // full preferred minimum would instead make the DEFAULT layout overlap: three rows of 391 dp plus
            // gaps need 1237 dp of the 944 dp a 1000 dp host leaves below the affordance band. The width axis
            // escapes this only because `columnsThatFit` can drop a column; rows have no such escape.
            val height = cellHeight.coerceAtLeast(invariantMinHeight(isContent))
            val xLtr = gap + col * (cellWidth + gap)
            val rawX = if (isRtl) usableWidth - xLtr - width else xLtr
            val rawY = topBand + gap + row * (cellHeight + gap)
            WindowState(
                id = id,
                title = title,
                // Fully visible: keep each window within the host (default layout never goes off-host).
                x = rawX.coerceIn(0f, maxOf(0f, hostWidth - width)),
                // Below the reserved top band AND within the host bottom (never under the fit toolbar).
                y = rawY.coerceIn(topBand, maxOf(topBand, hostHeight - height)),
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
    contentWindowIds: Set<String> = emptySet(),
) {

    /**
     * Ids of content windows (Agent/Comm) that get the wider tiled min-width (CYP-26 §2.2). A `var`
     * because the dynamic window set (CYP-100/S14) can gain/lose agents at runtime — [resetTo]/[syncWindows]
     * refresh it so a newly-added agent window still earns the 320 dp content floor.
     */
    private var contentWindowIds: Set<String> = contentWindowIds

    var windows: List<WindowState> by mutableStateOf(initial)
        private set

    /**
     * Stable page/registration order of window ids — **never** reordered by focus. The canvas [windows]
     * list encodes z-order (focus moves to the end); the phone pager (CYP-50/S10) keys its pages off this
     * stable order instead, so giving a window focus never re-sorts the pages (CYP-54 §2). Membership can
     * change at runtime via [syncWindows]/[resetTo] (CYP-100), which keep it in lock-step with [windows].
     */
    var windowOrder: List<String> by mutableStateOf(initial.map { it.id })
        private set

    /**
     * Windows in the stable [windowOrder] (not z-order) — the phone pager's page list. Reads the
     * snapshot-backed [windows] so geometry/title changes still recompose, while the **order** stays
     * fixed regardless of focus.
     */
    val orderedWindows: List<WindowState>
        get() = windowOrder.mapNotNull { id -> windows.firstOrNull { it.id == id } }

    /**
     * CYP-241 — transient Expand/Restore anchors: `id → the window's geometry BEFORE its Expand`. Snapshot-backed
     * so the titlebar `stateDescription` recomposes. **Not persisted** (CYP-204 persists geometry, NOT this) →
     * no stale "restore to last session"; session-local. Presence ⇒ Restore available; absence ⇒ (re-)Expand. Any
     * real user [moveBy]/[resizeBy] on a window, or a global [fit], clears its/all anchor(s) — the automatic,
     * threshold-free invalidation of §2.
     */
    var expandAnchors: Map<String, WindowState> by mutableStateOf(emptyMap())
        private set

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
            // CYP-338: the same class-scoped floor on the clamp path — a host resize must not squeeze a
            // content window below its composer.
            val resized = WindowReducer.clampSizeToBounds(
                it, width, height, minHeight = WindowReducer.invariantMinHeight(it.id in contentWindowIds),
            )
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
        // CYP-241 §4: a global re-tile assigns fresh geometry to ALL windows → every Restore anchor is now a lie
        // ("the old position" no longer exists). Drop them all; a later double-click starts a fresh Expand cycle.
        expandAnchors = emptyMap()
    }

    /**
     * Replaces the whole window set and lays it out as a fresh full tile (CYP-100). Used for the **first**
     * layout once the host is measured — every window is new, so there is nothing to preserve. Membership
     * and [windowOrder] are set from [desired] (registration order).
     */
    fun resetTo(
        desired: List<Pair<String, String>>,
        contentWindowIds: Set<String> = this.contentWindowIds,
        isRtl: Boolean = false,
    ) {
        this.contentWindowIds = contentWindowIds
        windowOrder = desired.map { it.first }
        windows = if (hostWidth > 0f && hostHeight > 0f) {
            WindowReducer.tile(desired, hostWidth, hostHeight, isRtl = isRtl, contentWindowIds = contentWindowIds)
        } else {
            // Host not measured yet: stack at the band so they are at least valid; resetTo runs again on measure.
            // CYP-338: even this placeholder honours the content floor — a window that renders before the first
            // measure must not do so without its composer. (The matching WIDTH gap here — 160 dp for content
            // windows instead of 320 — is spec §6's side-finding and a separate ticket; left untouched.)
            desired.map { (id, title) ->
                val isContent = id in contentWindowIds
                val h = if (isContent) TILED_CONTENT_WINDOW_MIN_HEIGHT else MIN_WINDOW_HEIGHT
                WindowState(id, title, 0f, HOST_AFFORDANCE_BAND, MIN_WINDOW_WIDTH, h)
            }
        }
    }

    /**
     * Reconciles the window set to [desired] **preserving existing windows' geometry** (CYP-100): a window
     * that stays keeps its exact position/size (only its title refreshes); a window that's gone is dropped;
     * a **new** window is placed in a free slot that doesn't overlap any current window (falling back to a
     * cascade when the host is full). z-order keeps the kept windows' relative stacking and puts new windows
     * on top (focused); [windowOrder] follows the registration order. A pure membership-identical call only
     * refreshes titles — so a host resize never re-tiles (that stays [updateHostSize]'s clamp job).
     */
    fun syncWindows(
        desired: List<Pair<String, String>>,
        contentWindowIds: Set<String> = this.contentWindowIds,
        isRtl: Boolean = false,
    ) {
        this.contentWindowIds = contentWindowIds
        val desiredIds = desired.map { it.first }
        val titles = desired.associate { it.first to it.second }
        val currentById = windows.associateBy { it.id }

        if (desiredIds.toSet() == currentById.keys) {
            // Same membership → only titles may have changed; positions are untouched.
            windows = windows.map { it.copy(title = titles[it.id] ?: it.title) }
            windowOrder = desiredIds
            return
        }

        // Build the synced set: keep existing geometry (refresh title), place each new window free.
        val placed = mutableListOf<WindowState>()
        val kept = mutableMapOf<String, WindowState>()
        for ((id, title) in desired) {
            val existing = currentById[id]
            if (existing != null) {
                kept[id] = existing.copy(title = title)
            } else {
                val newWindow = placeNewWindow(placed + kept.values, id, title, isRtl)
                placed += newWindow
            }
        }
        val byId = kept + placed.associateBy { it.id }

        // z-order: kept windows in their existing relative order, then the new ones on top (focused last).
        val keptZOrder = windows.mapNotNull { if (it.id in byId && it.id in currentById) it.id else null }
        val newZOrder = desiredIds.filter { it !in currentById }
        windows = (keptZOrder + newZOrder).mapNotNull { byId[it] }
        windowOrder = desiredIds
    }

    /** First non-overlapping, fully-visible slot below the affordance band for a new window (CYP-100). */
    private fun placeNewWindow(existing: List<WindowState>, id: String, title: String, isRtl: Boolean): WindowState {
        val minWidth = if (id in contentWindowIds) TILED_CONTENT_WINDOW_MIN_WIDTH else MIN_WINDOW_WIDTH
        // CYP-338: the height twin of minWidth, and the reported bug. An agent window that arrives after the
        // first layout lands here; at the plain 120 dp floor it renders without transcript and without composer.
        val minHeight = if (id in contentWindowIds) TILED_CONTENT_WINDOW_MIN_HEIGHT else MIN_WINDOW_HEIGHT
        val w = if (hostWidth > 0f) minWidth.coerceAtMost(maxOf(minWidth, hostWidth)) else minWidth
        val h = if (hostHeight > 0f) minHeight.coerceAtMost(maxOf(minHeight, hostHeight - HOST_AFFORDANCE_BAND)) else minHeight
        if (hostWidth <= 0f || hostHeight <= 0f) return WindowState(id, title, 0f, HOST_AFFORDANCE_BAND, w, h)

        val gap = 16f
        var y = HOST_AFFORDANCE_BAND + gap
        while (y + h <= hostHeight) {
            var x = gap
            while (x + w <= hostWidth) {
                val candidate = WindowState(id, title, if (isRtl) hostWidth - x - w else x, y, w, h)
                if (existing.none { overlaps(it, candidate) }) return candidate
                x += gap * 2f
            }
            y += gap * 2f
        }
        // Host full: cascade from the band, clamped fully visible.
        val offset = existing.size * 24f
        val cascadeX = (gap + offset % maxOf(1f, hostWidth - w)).coerceIn(0f, maxOf(0f, hostWidth - w))
        val cascadeY = (HOST_AFFORDANCE_BAND + gap + offset % maxOf(1f, hostHeight - HOST_AFFORDANCE_BAND - h))
            .coerceIn(HOST_AFFORDANCE_BAND, maxOf(HOST_AFFORDANCE_BAND, hostHeight - h))
        return WindowState(id, title, cascadeX, cascadeY, w, h)
    }

    private fun overlaps(a: WindowState, b: WindowState): Boolean =
        a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height

    fun focus(id: String) {
        windows = WindowReducer.bringToFront(windows, id)
    }

    fun moveBy(id: String, dx: Float, dy: Float) {
        val moved = WindowReducer.moveBy(windows, id, dx, dy)
        windows = moved.map {
            if (it.id == id) WindowReducer.clampToBounds(it, hostWidth, hostHeight) else it
        }
        clearExpandAnchor(id) // CYP-241 §2: a real user move invalidates the Restore anchor (any source).
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
            // CYP-338: without this the user simply drags the composer back out of an agent window.
            minHeight = WindowReducer.invariantMinHeight(id in contentWindowIds),
            maxWidth = maxWidth,
            maxHeight = maxHeight,
        )
        clearExpandAnchor(id) // CYP-241 §2: a real user resize invalidates the Restore anchor (any source).
    }

    /** CYP-241 — is [id] currently Expanded (a Restore anchor exists) → drives the titlebar `stateDescription`. */
    fun isExpanded(id: String): Boolean = expandAnchors.containsKey(id)

    /**
     * CYP-241 — titlebar double-click Expand+Center / Restore toggle (§2). **Anchor absent** → Expand: remember the
     * current geometry as the anchor, set the window to the [WindowReducer.expandCentered] target. **Anchor present**
     * → Restore: geometry back to the anchor (defensively re-clamped), drop the anchor. Either way `focus(id)` (§5).
     * A 2nd double-click after a manual move/resize (anchor cleared) is a **fresh** Expand — never a dead click (§2).
     * Sets geometry DIRECTLY (not via [moveBy]/[resizeBy]) so it manages the anchor explicitly and doesn't self-clear.
     * No-op until the host is measured.
     */
    fun toggleExpand(id: String) {
        if (hostWidth <= 0f || hostHeight <= 0f) return
        val current = windows.firstOrNull { it.id == id } ?: return
        val anchor = expandAnchors[id]
        if (anchor == null) {
            val target = WindowReducer.expandCentered(current, hostWidth, hostHeight, isContent = id in contentWindowIds)
            expandAnchors = expandAnchors + (id to current)
            windows = windows.map { if (it.id == id) target else it }
        } else {
            val restored = WindowReducer.clampToBounds(
                WindowReducer.clampSizeToBounds(
                    anchor, hostWidth, hostHeight,
                    minHeight = WindowReducer.invariantMinHeight(id in contentWindowIds), // CYP-338
                ),
                hostWidth, hostHeight,
            )
            expandAnchors = expandAnchors - id
            windows = windows.map { if (it.id == id) restored else it }
        }
        focus(id)
    }

    /** Drop [id]'s transient Expand anchor (a real move/resize happened) — no-op if none. */
    private fun clearExpandAnchor(id: String) {
        if (expandAnchors.containsKey(id)) expandAnchors = expandAnchors - id
    }
}
