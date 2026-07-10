package com.tneff.cyppieagents.window

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-349 — the placeholder geometry [WindowManagerState.resetTo] hands out **before the host is measured**.
 *
 * **This path is unreachable in the app today**, and that is stated rather than hidden: `AgentShell` calls
 * `resetTo` only from inside `if (hostWidth > 0f && hostHeight > 0f)`. Measured, not assumed — replacing the
 * branch body with `error(…)` and running the full suite (jvm + wasmJs + js) stays green.
 *
 * So this is **not** a regression test for a live defect; it pins the *intent*. Two reasons it is worth having:
 *
 *  1. The next change to the call order makes the branch reachable. A window that renders before the first
 *     measure must not appear at a floor that cuts off its transcript and composer.
 *  2. The real defect was never the number — it was that `resetTo` carried a **second, hand-inlined sizing rule**
 *     beside `placeNewWindow`. That is exactly how it ended up *right* on the height (CYP-338 fixed it there) and
 *     *wrong* on the width (160 dp for a content window) at the same moment. Both axes now come from one place;
 *     these assertions fail the day someone re-inlines them and they drift again.
 */
class WindowUnmeasuredHostFallbackTest {

    private val content = setOf("backend", "comm")

    /** A state whose host was never measured — `hostWidth`/`hostHeight` stay 0. */
    private fun unmeasured() = WindowManagerState(emptyList(), content)

    @Test
    fun contentWindows_keepBothContentFloors_beforeTheHostIsMeasured() {
        val state = unmeasured()
        state.resetTo(listOf("backend" to "Backend", "comm" to "Kommunikation"), content)

        for (w in state.windows) {
            assertEquals(
                TILED_CONTENT_WINDOW_MIN_WIDTH, w.width,
                "content window '${w.id}' must keep the content width floor before the first measure, " +
                    "not the plain ${MIN_WINDOW_WIDTH}dp — it carries a composer",
            )
            assertEquals(
                TILED_CONTENT_WINDOW_MIN_HEIGHT, w.height,
                "content window '${w.id}' must keep the content height floor before the first measure",
            )
        }
    }

    @Test
    fun toolWindows_keepThePlainFloors_beforeTheHostIsMeasured() {
        // The other half of the rule: the content floor is a floor for CONTENT windows. Widening every window
        // would be the mirror-image mistake, and this assertion is what stops a blanket "just use 320" fix.
        val state = unmeasured()
        state.resetTo(listOf("acl" to "Zugriffe", "settings" to "Einstellungen"), content)

        for (w in state.windows) {
            assertEquals(MIN_WINDOW_WIDTH, w.width, "tool window '${w.id}' keeps the plain width floor")
            assertEquals(MIN_WINDOW_HEIGHT, w.height, "tool window '${w.id}' keeps the plain height floor")
        }
    }

    @Test
    fun mixedSet_sizesEachWindowByItsOwnKind() {
        val state = unmeasured()
        state.resetTo(listOf("backend" to "Backend", "acl" to "Zugriffe"), content)

        val agent = state.windows.single { it.id == "backend" }
        val acl = state.windows.single { it.id == "acl" }
        assertEquals(TILED_CONTENT_WINDOW_MIN_WIDTH, agent.width)
        assertEquals(TILED_CONTENT_WINDOW_MIN_HEIGHT, agent.height)
        assertEquals(MIN_WINDOW_WIDTH, acl.width)
        assertEquals(MIN_WINDOW_HEIGHT, acl.height)
    }

    @Test
    fun theFallbackAgreesWithTheMeasuredPlacementPath_onBothAxes() {
        // The invariant behind the fix: `resetTo`'s placeholder and `placeNewWindow`'s unmeasured return must
        // hand out the same floors for the same window kind. They diverged for months on the width alone.
        val viaReset = unmeasured().apply { resetTo(listOf("backend" to "Backend"), content) }.windows.single()
        val viaSync = unmeasured().apply { syncWindows(listOf("backend" to "Backend"), content) }.windows.single()

        assertEquals(viaReset.width, viaSync.width, "both unmeasured placement paths must agree on the width")
        assertEquals(viaReset.height, viaSync.height, "both unmeasured placement paths must agree on the height")
    }
}
