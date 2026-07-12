package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteSessionState
import com.tneff.cyppieagents.net.hub.trust.InMemoryPinnedHubStore
import com.tneff.cyppieagents.net.hub.trust.MapPresentedHubKeySource
import com.tneff.cyppieagents.net.hub.trust.OobConfirmState
import com.tneff.cyppieagents.net.hub.trust.PendingOobConfirmations
import com.tneff.cyppieagents.net.hub.trust.TofuHubTrust
import com.tneff.cyppieagents.net.hub.trust.TrustConfirmationRejectedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-510 — the live OOB-confirm wiring: the trust layer's [OobConfirmState] drives a real [OobConfirmMount]
 * (was null/INERT). Proves the derivation ([buildLiveOobMount]) and — the Reviewer-adversarial trust path — the
 * **CI-1 binding**: the fingerprint the operator confirms IS the key `TofuHubTrust` pins (`onConfirm` → real
 * pin; `onReject` → fail-closed teardown, nothing pinned). The trust-layer approve/reject/no-pin invariants
 * themselves are pinned by `TofuHubTrustTest` (CYP-478); here we pin the **wiring** from the mount's buttons
 * through the coordinator to that trust layer, plus that a live mount retires the provisional disclosure (CYP-505).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Cyp510LiveOobWiringTest {

    private val hub = HubDescriptor("hub-a", "host-a", online = true, defaultPort = 8787, lastSeen = 1L)
    private fun key(seed: Int) = ByteArray(32) { (it + seed).toByte() }

    /** A coordinator whose OOB state + presented keys are scripted; records approve/reject. */
    private class RecordingCoordinator(
        stateValue: OobConfirmState,
        private val presented: Map<String, ByteArray> = emptyMap(),
    ) : OobConfirmCoordinator {
        override val state: StateFlow<OobConfirmState> = MutableStateFlow(stateValue)
        var approved = 0
        var rejected = 0
        override fun approve() { approved++ }
        override fun reject() { rejected++ }
        override suspend fun presentedStatic(hubId: String): ByteArray? = presented[hubId]?.copyOf()
    }

    // ---------- Group A — buildLiveOobMount derivation ----------

    @Test
    fun awaiting_derivesMount_provisionalFalse_withPresentedBytes_wiredToApproveReject() = runTest {
        val bytes = key(3)
        val coord = RecordingCoordinator(OobConfirmState.Awaiting("hub-a", "fp"), mapOf("hub-a" to bytes))
        val mount = buildLiveOobMount(coord, hub, coord.state.value)
        assertNotNull(mount, "an Awaiting for this hub yields a live mount")
        assertFalse(mount.provisional, "live ⇒ provisional disclosure retires (CYP-505)")
        assertContentEquals(bytes, mount.hubDhPubKey, "the fingerprint bytes ARE the presented key (CI-1)")
        mount.onConfirm(); assertEquals(1, coord.approved, "onConfirm → coordinator.approve")
        mount.onReject(); assertEquals(1, coord.rejected, "onReject → coordinator.reject")
    }

    @Test
    fun idle_derivesNoMount() = runTest {
        val coord = RecordingCoordinator(OobConfirmState.Idle, mapOf("hub-a" to key(3)))
        assertNull(buildLiveOobMount(coord, hub, coord.state.value), "no confirmation pending ⇒ no mount (spinner)")
    }

    @Test
    fun awaitingOtherHub_derivesNoMount_q5() = runTest {
        val coord = RecordingCoordinator(OobConfirmState.Awaiting("hub-b", "fp"), mapOf("hub-b" to key(3)))
        assertNull(buildLiveOobMount(coord, hub, coord.state.value), "an Awaiting for a DIFFERENT hub is not ours (Q5)")
    }

    @Test
    fun awaiting_noPresentedBytes_failsClosedToNull() = runTest {
        val coord = RecordingCoordinator(OobConfirmState.Awaiting("hub-a", "fp"), presented = emptyMap())
        assertNull(
            buildLiveOobMount(coord, hub, coord.state.value),
            "no usable presented key ⇒ NO mount (never a fabricated fingerprint — fail closed)",
        )
    }

    // ---------- Group B — end-to-end through a REAL TofuHubTrust (CI-1 binding, security) ----------

    @Test
    fun mountOnConfirm_pinsExactlyThePresentedKeyShown() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val bytes = key(5)
        val store = InMemoryPinnedHubStore()
        val presented = MapPresentedHubKeySource(mapOf("hub-a" to bytes))
        val pending = PendingOobConfirmations()
        val trust = TofuHubTrust(presented, store, pending)          // the real trust layer
        val coordinator = LiveOobConfirmCoordinator(pending, presented) // SAME pending + presented as the trust

        val resolve = scope.launch { trust.resolve("hub-a") } // FirstUse ⇒ suspends at awaitConfirmation
        advanceUntilIdle()
        assertIs<OobConfirmState.Awaiting>(pending.state.value, "trust.resolve is awaiting the OOB confirm")

        val mount = buildLiveOobMount(coordinator, hub, coordinator.state.value)
        assertNotNull(mount)
        mount.onConfirm() // the operator confirms the shown fingerprint
        advanceUntilIdle()

        assertContentEquals(bytes, store.pinnedKey("hub-a"), "approve pins the presented key (persisted)")
        assertContentEquals(
            mount.hubDhPubKey, store.pinnedKey("hub-a"),
            "CI-1: the key that got pinned IS exactly the one the mount displayed",
        )
        assertTrue(resolve.isCompleted)
        scope.cancel()
    }

    @Test
    fun mountOnReject_failsClosed_nothingPinned_connectUnwinds() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val bytes = key(5)
        val store = InMemoryPinnedHubStore()
        val presented = MapPresentedHubKeySource(mapOf("hub-a" to bytes))
        val pending = PendingOobConfirmations()
        val trust = TofuHubTrust(presented, store, pending)
        val coordinator = LiveOobConfirmCoordinator(pending, presented)

        var thrown: Throwable? = null
        val resolve = scope.launch {
            try {
                trust.resolve("hub-a")
            } catch (e: TrustConfirmationRejectedException) {
                thrown = e
            }
        }
        advanceUntilIdle()
        val mount = buildLiveOobMount(coordinator, hub, coordinator.state.value)
        assertNotNull(mount)
        mount.onReject() // the operator rejects
        advanceUntilIdle()

        assertNull(store.pinnedKey("hub-a"), "a rejected first-use is NEVER pinned (fail-closed)")
        val t = thrown
        assertTrue(t is TrustConfirmationRejectedException, "reject unwinds the connect fail-closed")
        scope.cancel()
    }

    // ---------- Group C — ViewModel integration ----------

    /** A remote feed that stalls at RELAY_DIALING (never CONNECTED) so the OOB Awaiting is what surfaces. */
    private val stallingRemoteFeed = RemoteConnectFeed { h ->
        flow {
            emit(RemoteSessionState(h.hubId, RemoteConnState.RELAY_DIALING))
            awaitCancellation()
        }
    }

    private fun vmWith(coordinator: OobConfirmCoordinator?, feed: RemoteConnectFeed, scope: CoroutineScope) =
        HubConnectViewModel(
            controlPlane = StubControlPlaneClient(hubs = listOf(hub)),
            credentials = StubHubCredentialRepository(),
            connectFeed = StubLocalConnectFeed(),
            remoteConnectFeed = feed,
            oobConfirm = coordinator,
            scope = scope,
        )

    @Test
    fun connectRemote_whenAwaiting_surfacesLiveMount_atTrustCheck() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val bytes = key(7)
        val coord = RecordingCoordinator(OobConfirmState.Awaiting("hub-a", "fp"), mapOf("hub-a" to bytes))
        val vm = vmWith(coord, stallingRemoteFeed, scope)
        vm.start(); advanceUntilIdle(); vm.selectHub(hub); vm.connectRemote(); advanceUntilIdle()

        val s = assertIs<HubConnectUiState.RemoteConnecting>(vm.state.value)
        assertEquals(RemoteConnState.TRUST_CHECK, s.remote.conn, "OOB pending surfaces at TRUST_CHECK")
        val mount = assertNotNull(s.oobConfirm, "the live mount is surfaced")
        assertFalse(mount.provisional)
        assertContentEquals(bytes, mount.hubDhPubKey)
        scope.cancel()
    }

    @Test
    fun connectRemote_nullCoordinator_isInert_noMount() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val vm = vmWith(coordinator = null, feed = StubRemoteConnectFeed(), scope = scope)
        vm.start(); advanceUntilIdle(); vm.selectHub(hub); vm.connectRemote(); advanceUntilIdle()

        val s = assertIs<HubConnectUiState.RemoteConnecting>(vm.state.value)
        assertNull(s.oobConfirm, "no coordinator ⇒ INERT: no live mount (byte-identical to CYP-471)")
        assertEquals(RemoteConnState.CONNECTED, s.remote.conn, "the feed's truth is untouched")
        scope.cancel()
    }
}
