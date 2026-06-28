package com.tneff.cyppieagents.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowReducerTest {

    private fun sample(): List<WindowState> = listOf(
        WindowState("a", "A", 0f, 0f, 200f, 150f),
        WindowState("b", "B", 10f, 10f, 200f, 150f),
        WindowState("c", "C", 20f, 20f, 200f, 150f),
    )

    @Test
    fun bringToFront_middleWindow_movesItToEnd() {
        val result = WindowReducer.bringToFront(sample(), "b")
        assertEquals(listOf("a", "c", "b"), result.map { it.id })
    }

    @Test
    fun bringToFront_alreadyFrontWindow_keepsOrder() {
        val result = WindowReducer.bringToFront(sample(), "c")
        assertEquals(listOf("a", "b", "c"), result.map { it.id })
    }

    @Test
    fun bringToFront_unknownId_returnsUnchanged() {
        val original = sample()
        val result = WindowReducer.bringToFront(original, "does-not-exist")
        assertEquals(original.map { it.id }, result.map { it.id })
    }

    @Test
    fun moveBy_targetWindow_offsetsOnlyThatWindow() {
        val result = WindowReducer.moveBy(sample(), "b", 5f, -3f)
        val b = result.first { it.id == "b" }
        assertEquals(15f, b.x)
        assertEquals(7f, b.y)
        // Untouched windows keep their original position.
        assertEquals(0f, result.first { it.id == "a" }.x)
        assertEquals(20f, result.first { it.id == "c" }.x)
    }

    @Test
    fun moveBy_unknownId_returnsUnchanged() {
        val original = sample()
        val result = WindowReducer.moveBy(original, "does-not-exist", 10f, 10f)
        assertEquals(original, result)
    }

    @Test
    fun clampToBounds_draggedPastRightAndBottom_keepsMinVisibleInside() {
        val window = WindowState("a", "A", 5000f, 5000f, 200f, 150f)
        val result = WindowReducer.clampToBounds(window, hostWidth = 1000f, hostHeight = 800f)
        // keepVisible default 48 → maxX = 1000-48, maxY = 800-48.
        assertEquals(952f, result.x)
        assertEquals(752f, result.y)
    }

    @Test
    fun clampToBounds_draggedPastLeftAndTop_keepsWindowReachable() {
        val window = WindowState("a", "A", -5000f, -5000f, 200f, 150f)
        val result = WindowReducer.clampToBounds(window, hostWidth = 1000f, hostHeight = 800f)
        // Left edge may go off-screen but keepVisible of the right side stays in (48 - width).
        assertEquals(48f - 200f, result.x)
        // Title bar is kept at the very top so it stays grabbable.
        assertEquals(0f, result.y)
    }

    @Test
    fun clampToBounds_withinBounds_returnsUnchanged() {
        val window = WindowState("a", "A", 100f, 80f, 200f, 150f)
        val result = WindowReducer.clampToBounds(window, hostWidth = 1000f, hostHeight = 800f)
        assertEquals(100f, result.x)
        assertEquals(80f, result.y)
    }

    @Test
    fun clampToBounds_unmeasuredHost_returnsUnchanged() {
        val window = WindowState("a", "A", 5000f, 5000f, 200f, 150f)
        val result = WindowReducer.clampToBounds(window, hostWidth = 0f, hostHeight = 0f)
        assertEquals(5000f, result.x)
        assertEquals(5000f, result.y)
    }

    @Test
    fun resizeBy_positiveDelta_growsWindow() {
        val result = WindowReducer.resizeBy(sample(), "a", 50f, 40f)
        val a = result.first { it.id == "a" }
        assertEquals(250f, a.width)
        assertEquals(190f, a.height)
    }

    @Test
    fun resizeBy_shrinkBelowMinimum_clampsToMinimum() {
        val result = WindowReducer.resizeBy(sample(), "a", -1000f, -1000f)
        val a = result.first { it.id == "a" }
        assertEquals(MIN_WINDOW_WIDTH, a.width)
        assertEquals(MIN_WINDOW_HEIGHT, a.height)
    }

    @Test
    fun resizeBy_growthPastMaximum_clampsToMaximum() {
        val result = WindowReducer.resizeBy(sample(), "a", 1000f, 1000f, maxWidth = 300f, maxHeight = 250f)
        val a = result.first { it.id == "a" }
        assertEquals(300f, a.width)
        assertEquals(250f, a.height)
    }

    @Test
    fun resizeBy_maximumBelowMinimum_neverGoesBelowMinimum() {
        // Degenerate host smaller than the minimum window: width/height stay at the minimum.
        val result = WindowReducer.resizeBy(sample(), "a", 1000f, 1000f, maxWidth = 10f, maxHeight = 10f)
        val a = result.first { it.id == "a" }
        assertEquals(MIN_WINDOW_WIDTH, a.width)
        assertEquals(MIN_WINDOW_HEIGHT, a.height)
    }

    @Test
    fun resizeDeltaForLayout_ltr_keepsSign() {
        assertEquals(12f, WindowReducer.resizeDeltaForLayout(12f, isRtl = false))
    }

    @Test
    fun resizeDeltaForLayout_rtl_invertsSign() {
        assertEquals(-12f, WindowReducer.resizeDeltaForLayout(12f, isRtl = true))
    }

    @Test
    fun clampSizeToBounds_windowLargerThanHost_shrinksToHost() {
        val window = WindowState("a", "A", 0f, 0f, 2000f, 2000f)
        val result = WindowReducer.clampSizeToBounds(window, hostWidth = 800f, hostHeight = 600f)
        assertEquals(800f, result.width)
        assertEquals(600f, result.height)
    }

    @Test
    fun clampSizeToBounds_windowWithinHost_returnsUnchanged() {
        val window = WindowState("a", "A", 0f, 0f, 300f, 200f)
        val result = WindowReducer.clampSizeToBounds(window, hostWidth = 800f, hostHeight = 600f)
        assertEquals(300f, result.width)
        assertEquals(200f, result.height)
    }

    @Test
    fun clampSizeToBounds_unmeasuredHost_returnsUnchanged() {
        val window = WindowState("a", "A", 0f, 0f, 2000f, 2000f)
        val result = WindowReducer.clampSizeToBounds(window, hostWidth = 0f, hostHeight = 0f)
        assertEquals(2000f, result.width)
        assertEquals(2000f, result.height)
    }

    @Test
    fun resizeHandle_constants_meetWcagMinimumTargetSize() {
        assertTrue(RESIZE_HANDLE_SIZE >= 24f) // WCAG 2.5.8
        assertTrue(RESIZE_HIT_SLOP >= RESIZE_HANDLE_SIZE)
    }

    @Test
    fun tile_threeItems_producesDistinctInBoundsPositions() {
        val items = listOf("po" to "PO", "fe" to "FE", "be" to "BE")
        val result = WindowReducer.tile(items, hostWidth = 1200f, hostHeight = 800f)

        assertEquals(3, result.size)
        // Tiled, not stacked: every window starts at a distinct position.
        assertEquals(3, result.map { it.x to it.y }.toSet().size)
        assertTrue(result.all { it.width >= MIN_WINDOW_WIDTH && it.height >= MIN_WINDOW_HEIGHT })
        assertTrue(result.all { it.x >= 0f && it.y >= 0f })
    }

    @Test
    fun tile_unmeasuredHost_stillKeepsMinimumSize() {
        val items = listOf("po" to "PO", "fe" to "FE")
        val result = WindowReducer.tile(items, hostWidth = 0f, hostHeight = 0f)

        assertEquals(2, result.size)
        assertTrue(result.all { it.width >= MIN_WINDOW_WIDTH && it.height >= MIN_WINDOW_HEIGHT })
    }

    @Test
    fun tile_emptyList_returnsEmpty() {
        assertEquals(emptyList(), WindowReducer.tile(emptyList(), 800f, 600f))
    }

    // --- CYP-26: responsive tiling (size-class column cap, fully-visible, content-min, RTL) ---

    @Test
    fun tile_mediumWidth_capsAtTwoColumns() {
        // 5 windows on a Medium host (600–839) → ≤ 2 columns (not sqrt(5)=3).
        val items = (1..5).map { "w$it" to "W$it" }
        val result = WindowReducer.tile(items, hostWidth = 760f, hostHeight = 700f)
        val distinctX = result.map { it.x }.toSet().size
        assertTrue(distinctX <= 2, "Medium host must cap at 2 columns, got $distinctX distinct x")
    }

    @Test
    fun tile_expandedWidth_usesSqrtColumns() {
        // 5 windows on an Expanded host (≥840) → uncapped sqrt → ceil(sqrt(5)) = 3 columns.
        val items = (1..5).map { "w$it" to "W$it" }
        val result = WindowReducer.tile(items, hostWidth = 1280f, hostHeight = 900f)
        assertEquals(3, result.map { it.x }.toSet().size, "Expanded host uses sqrt columns (3 for 5 windows)")
    }

    @Test
    fun tile_defaultLayout_everyWindowIsFullyVisible() {
        val items = (1..5).map { "w$it" to "W$it" }
        val host = 760f to 700f
        val result = WindowReducer.tile(items, hostWidth = host.first, hostHeight = host.second)
        // Default layout = fully visible (not the 48 dp manual floor): each window wholly within the host.
        assertTrue(result.all { it.x >= 0f && it.x + it.width <= host.first + 0.01f }, "x within host")
        assertTrue(result.all { it.y >= 0f && it.y + it.height <= host.second + 0.01f }, "y within host")
    }

    @Test
    fun tile_contentWindow_getsWiderMinWidthThanReadingWindow() {
        // Host where the raw cell width lands between 160 and 320 so the two floors diverge.
        val items = listOf("comm" to "Comm", "acl" to "ACL")
        val result = WindowReducer.tile(items, hostWidth = 680f, hostHeight = 600f, contentWindowIds = setOf("comm"))
        val comm = result.first { it.id == "comm" }
        val acl = result.first { it.id == "acl" }
        assertEquals(TILED_CONTENT_WINDOW_MIN_WIDTH, comm.width, "content window floored at 320")
        assertTrue(acl.width < TILED_CONTENT_WINDOW_MIN_WIDTH, "reading window keeps the narrower cell")
    }

    @Test
    fun tile_narrowHost_contentFloorWouldOverflow_clampKeepsFullyVisible() {
        // Medium host too narrow for two 320 dp content windows: the 320 floor pushes the 2nd column's
        // rawX past host-width. The fully-visible clamp must pull it back in — WITHOUT the clamp this
        // window would be off-host (x + width > host). This is the case that makes the clamp load-bearing
        // (the natural-fit tests leave it a no-op). Mutation: x = rawX → this test goes RED.
        val items = listOf("comm" to "Comm", "acl-but-content" to "X")
        val host = 640f // Medium (600–839), 2 columns; cell ≈ 296 dp < 320 dp content floor
        val result = WindowReducer.tile(
            items, hostWidth = host, hostHeight = 600f, contentWindowIds = setOf("comm", "acl-but-content"),
        )
        assertTrue(result.all { it.width == TILED_CONTENT_WINDOW_MIN_WIDTH }, "both content windows floored to 320")
        assertTrue(
            result.all { it.x >= 0f && it.x + it.width <= host + 0.01f },
            "320-floor must not push a window off-host — clamp keeps it fully visible: ${result.map { it.x to it.width }}",
        )
    }

    @Test
    fun tile_rtl_mirrorsColumnsToStartEdge() {
        val items = listOf("a" to "A", "b" to "B")
        val ltr = WindowReducer.tile(items, hostWidth = 800f, hostHeight = 600f, isRtl = false)
        val rtl = WindowReducer.tile(items, hostWidth = 800f, hostHeight = 600f, isRtl = true)
        // First item sits at the (visual) left in LTR, mirrored to the right in RTL → larger x.
        assertTrue(rtl[0].x > ltr[0].x, "RTL mirrors the first column to the start (right) edge")
        // Still fully visible after mirroring.
        assertTrue(rtl.all { it.x >= 0f && it.x + it.width <= 800f + 0.01f })
    }
}
