package com.tneff.cyppieagents.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-100: [WindowManagerState.syncWindows] reconciles a changed window set **without disturbing the
 * windows that stay** — an added agent gets a free slot, a removed one is dropped, and a same-membership
 * call only refreshes titles. Pure (no Compose). Each guard is mutation-provable:
 * - preserve: replace syncWindows with a full re-tile → the moved window's position test goes RED.
 * - free slot: drop the overlap check in placeNewWindow → the non-overlap test goes RED.
 * - drop gone / order: skip removal or the windowOrder update → those tests go RED.
 */
class WindowSyncTest {

    private fun overlaps(a: WindowState, b: WindowState): Boolean =
        a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height

    /** A host with explicit SMALL windows so there is genuine free space for a new one to land in. */
    private fun smallState(vararg windows: WindowState): WindowManagerState {
        val state = WindowManagerState(windows.toList())
        state.updateHostSize(1200f, 800f)
        return state
    }

    private fun win(id: String, x: Float, y: Float) = WindowState(id, id.uppercase(), x, y, 200f, 150f)

    @Test
    fun resetTo_laysOutTheWholeSet() {
        val state = WindowManagerState(emptyList())
        state.updateHostSize(1200f, 800f)
        state.resetTo(listOf("a" to "A", "b" to "B"))
        assertEquals(setOf("a", "b"), state.windows.map { it.id }.toSet())
        assertEquals(listOf("a", "b"), state.windowOrder)
    }

    @Test
    fun syncWindows_addNew_preservesExistingPositions_andPlacesNewFree() {
        val state = smallState(win("a", 16f, 72f), win("b", 320f, 72f))
        // Drag 'a' to a custom spot the user chose.
        state.moveBy("a", 37f, 41f)
        val aBefore = state.windows.first { it.id == "a" }
        val bBefore = state.windows.first { it.id == "b" }

        state.syncWindows(listOf("a" to "A", "b" to "B", "c" to "C"))

        // The kept windows keep their EXACT geometry — no reshuffle on add.
        val aAfter = state.windows.first { it.id == "a" }
        val bAfter = state.windows.first { it.id == "b" }
        assertEquals(aBefore.x, aAfter.x); assertEquals(aBefore.y, aAfter.y)
        assertEquals(bBefore.x, bAfter.x); assertEquals(bBefore.y, bAfter.y)
        // The new window is present and overlaps neither kept window (a free slot).
        val c = state.windows.first { it.id == "c" }
        assertTrue(
            state.windows.filter { it.id != "c" }.none { overlaps(it, c) },
            "new window must be placed in a free, non-overlapping slot",
        )
        assertEquals(listOf("a", "b", "c"), state.windowOrder)
    }

    @Test
    fun syncWindows_removeGoneWindow() {
        val state = smallState(win("a", 16f, 72f), win("b", 320f, 72f))
        state.syncWindows(listOf("a" to "A"))
        assertNull(state.windows.firstOrNull { it.id == "b" })
        assertEquals(listOf("a"), state.windowOrder)
    }

    @Test
    fun syncWindows_sameMembership_keepsPositions_refreshesTitle() {
        val state = smallState(win("a", 16f, 72f), win("b", 320f, 72f))
        state.moveBy("a", 50f, 60f)
        val aBefore = state.windows.first { it.id == "a" }

        state.syncWindows(listOf("a" to "A-renamed", "b" to "B"))

        val aAfter = state.windows.first { it.id == "a" }
        // Membership unchanged → never re-tiles: positions are byte-identical, only the title refreshes.
        assertEquals(aBefore.x, aAfter.x); assertEquals(aBefore.y, aAfter.y)
        assertEquals("A-renamed", aAfter.title)
    }
}
