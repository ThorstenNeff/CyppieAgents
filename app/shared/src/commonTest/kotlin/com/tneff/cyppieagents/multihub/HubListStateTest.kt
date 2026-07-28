package com.tneff.cyppieagents.multihub

import com.tneff.cyppieagents.connect.HubDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-856 — the load-bearing **honest-empty tri-state** tooth (pure, no render): the hub-list state models THREE
 * distinct outcomes — unknown ≠ honest-empty ≠ load-error — and the render precedence is fail-closed (error beats
 * everything). This is the parity of web-ts's `hubList.ts` contract.
 */
class HubListStateTest {

    private fun hub(id: String) =
        HubDescriptor(hubId = id, name = "Hub $id", online = true, defaultPort = 8787, lastSeen = 0L)

    @Test
    fun emptyHubList_isUnknown_notEmpty_notError() {
        val s = HubListState.EMPTY
        assertFalse(s.loaded, "before any load the list is NOT loaded (unknown)")
        assertFalse(s.loadError)
        assertTrue(s.hubs.isEmpty())
        assertEquals(HubSwitcherOutcome.Unknown, s.outcome(), "unknown must NOT render as a confident empty")
    }

    @Test
    fun loadHubList_withHubs_isHubsOutcome() {
        val s = loadHubList(listOf(hub("a"), hub("b")))
        assertTrue(s.loaded)
        assertFalse(s.loadError)
        assertEquals(HubSwitcherOutcome.Hubs(listOf(hub("a"), hub("b"))), s.outcome())
    }

    @Test
    fun loadHubList_emptyList_isHonestEmpty_notUnknown() {
        val s = loadHubList(emptyList())
        assertTrue(s.loaded, "a successful ZERO-hub load is loaded (an honest empty), NOT unknown")
        assertFalse(s.loadError)
        assertEquals(HubSwitcherOutcome.Empty, s.outcome())
    }

    @Test
    fun failHubList_isError_failClosed_clearsHubs() {
        val s = failHubList()
        assertTrue(s.loadError)
        assertFalse(s.loaded, "a failed load is NOT loaded — it is neither loaded nor unknown")
        assertTrue(s.hubs.isEmpty(), "fail-closed: a failed load clears any stale hubs")
        assertEquals(HubSwitcherOutcome.Error, s.outcome())
    }

    @Test
    fun outcome_triState_neverConflated() {
        // The three flag combinations map to three DISTINCT outcomes — folding any two would erase the honesty.
        assertEquals(HubSwitcherOutcome.Unknown, HubListState(emptyList(), loaded = false, loadError = false).outcome())
        assertEquals(HubSwitcherOutcome.Empty, HubListState(emptyList(), loaded = true, loadError = false).outcome())
        assertEquals(HubSwitcherOutcome.Error, HubListState(emptyList(), loaded = false, loadError = true).outcome())
    }

    @Test
    fun outcome_error_beatsStaleLoadedHubs_failClosedPrecedence() {
        // Even with a stale non-empty list, a load-error wins (error → unknown → empty → list). A failed refresh must
        // never render the old hubs as if current. Mutation: dropping the `loadError -> Error` branch → this reddens.
        val s = HubListState(hubs = listOf(hub("x")), loaded = false, loadError = true)
        assertEquals(HubSwitcherOutcome.Error, s.outcome())
    }

    @Test
    fun loadHubList_copiesDefensively() {
        val src = mutableListOf(hub("a"))
        val s = loadHubList(src)
        src.add(hub("b"))
        assertEquals(1, s.hubs.size, "a later mutation of the caller's list must NOT re-write the stored one")
    }
}
