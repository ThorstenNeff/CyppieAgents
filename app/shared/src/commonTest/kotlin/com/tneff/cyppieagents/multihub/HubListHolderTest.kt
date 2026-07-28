package com.tneff.cyppieagents.multihub

import com.tneff.cyppieagents.connect.HubDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-856 — the [HubListHolder] maps the injected source onto the honest tri-state: a success loads (honest-empty
 * possible), a failure FAILS CLOSED to a load-error (never a misleading "no hubs"). The `Dispatchers.Unconfined`
 * scope settles the `refresh()` launch synchronously (the stub source never really suspends).
 */
class HubListHolderTest {

    private fun hub(id: String) =
        HubDescriptor(hubId = id, name = "Hub $id", online = true, defaultPort = 8787, lastSeen = 0L)

    @Test
    fun refresh_success_loadsHonestly() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val holder = HubListHolder(StubHubListSource(descriptors = listOf(hub("a"), hub("b"))), scope)
            holder.refresh()
            val s = holder.state.value
            assertTrue(s.loaded)
            assertFalse(s.loadError)
            assertEquals(2, s.hubs.size)
            assertEquals(HubSwitcherOutcome.Hubs(listOf(hub("a"), hub("b"))), s.outcome())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun refresh_emptySource_isHonestEmpty_notError() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val holder = HubListHolder(StubHubListSource(descriptors = emptyList()), scope)
            holder.refresh()
            val s = holder.state.value
            assertTrue(s.loaded, "a successful zero-hub load is an honest empty, not an error")
            assertFalse(s.loadError)
            assertEquals(HubSwitcherOutcome.Empty, s.outcome())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun refresh_failure_failsClosed_notEmpty() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val holder = HubListHolder(StubHubListSource(failing = true), scope)
            holder.refresh()
            val s = holder.state.value
            assertTrue(s.loadError, "an unreachable source must FAIL CLOSED to a load-error")
            assertFalse(s.loaded, "a failed load is NOT a loaded empty — the switcher must show Error+Retry, not 'no hubs'")
            assertEquals(HubSwitcherOutcome.Error, s.outcome())
        } finally {
            scope.cancel()
        }
    }
}
