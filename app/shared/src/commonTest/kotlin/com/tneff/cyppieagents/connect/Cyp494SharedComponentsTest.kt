package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.trust.InMemoryPinnedHubStore
import com.tneff.cyppieagents.net.hub.trust.OobConfirmState
import com.tneff.cyppieagents.net.hub.trust.TrustConfirmationRejectedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-494/510 composition-root — [buildSharedHubTrustComponents] proves ①② **at the object** (Doc 19 §2.1): the
 * trust and the OOB coordinator SHARE one `PendingOobConfirmations` (so `approve`/`reject` wake the trust's OWN
 * FirstUse waiter, and the displayed fingerprint IS the pinned key — display == pinned) and one `of(hub)`
 * presented source (deterministic-per-session, not a `fromControlPlane` re-query → no TOCTOU).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Cyp494SharedComponentsTest {

    private val key = ByteArray(32) { (it + 1).toByte() }
    private fun hub() = HubDescriptor(
        "hub-a", "host-a", online = true, defaultPort = 8787, lastSeen = 1L,
        dhPubKey = Base64.Default.encode(key),
    )

    @Test
    fun approveOnCoordinator_pinsTheKeyItDisplays_displayEqualsPinned() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val store = InMemoryPinnedHubStore()
        val c = buildSharedHubTrustComponents(hub(), store)

        val resolve = scope.launch { c.trust.resolve("hub-a") } // FirstUse ⇒ suspends on the SHARED pending
        advanceUntilIdle()
        assertIs<OobConfirmState.Awaiting>(
            c.oobConfirm.state.value,
            "the coordinator sees the trust's Awaiting — proof they share one PendingOobConfirmations",
        )
        val displayed = c.oobConfirm.presentedStatic("hub-a")

        c.oobConfirm.approve() // wakes the trust's OWN waiter (① same stateful instance)
        advanceUntilIdle()

        assertContentEquals(key, store.pinnedKey("hub-a"), "approve pins the presented key")
        assertContentEquals(displayed, store.pinnedKey("hub-a"), "① display == pinned (no confused deputy)")
        assertTrue(resolve.isCompleted)
        scope.cancel()
    }

    @Test
    fun rejectOnCoordinator_wakesTheTrustWaiter_failClosed_nothingPinned() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val store = InMemoryPinnedHubStore()
        val c = buildSharedHubTrustComponents(hub(), store)
        var thrown: Throwable? = null
        val resolve = scope.launch {
            try {
                c.trust.resolve("hub-a")
            } catch (e: TrustConfirmationRejectedException) {
                thrown = e
            }
        }
        advanceUntilIdle()
        c.oobConfirm.reject() // wakes the trust's OWN waiter to fail closed
        advanceUntilIdle()

        assertNull(store.pinnedKey("hub-a"), "a rejected first-use is never pinned")
        val t = thrown
        assertTrue(t is TrustConfirmationRejectedException, "reject unwinds the trust's resolve fail-closed")
        assertTrue(resolve.isCompleted)
        scope.cancel()
    }

    @Test
    fun presentedStatic_isTheDeterministicHubKey_notARegistryQuery() = runTest {
        // ②: of(hub) is bound to THIS hub — its own dhPubKey, and nothing for a different id (no registry lookup).
        val c = buildSharedHubTrustComponents(hub(), InMemoryPinnedHubStore())
        assertContentEquals(key, c.oobConfirm.presentedStatic("hub-a"), "of(hub) resolves THIS hub's key deterministically")
        assertNull(c.oobConfirm.presentedStatic("some-other-hub"), "of(hub) is hub-scoped, not a registry lookup (no TOCTOU)")
    }
}
