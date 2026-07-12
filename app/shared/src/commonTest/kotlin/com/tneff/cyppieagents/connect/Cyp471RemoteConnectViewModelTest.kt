package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * CYP-471 — the remote-connect VM honesty teeth. `connectRemote` drives the honest [RemoteConnState] progression;
 * `connected` is reached ONLY on `CONNECTED`; a terminal failure surfaces as `LOST` + the feed's `RemoteFailure`
 * (never a fake connected); and the **Q5 switch tears down the old hub before the new (exactly-one-hub)**. VM on an
 * [UnconfinedTestDispatcher] so its collectors settle eagerly (CYP-419 lesson).
 */
class Cyp471RemoteConnectViewModelTest {

    private val hubA = HubDescriptor("hub-a", "host-a", online = true, defaultPort = 8787, lastSeen = 1L)
    private val hubB = HubDescriptor("hub-b", "host-b", online = true, defaultPort = 8787, lastSeen = 1L)

    private fun TestScope.vm(feed: RemoteConnectFeed) = HubConnectViewModel(
        controlPlane = StubControlPlaneClient(hubs = listOf(hubA, hubB)),
        credentials = StubHubCredentialRepository(),
        connectFeed = StubLocalConnectFeed(),
        remoteConnectFeed = feed,
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
    )

    @Test
    fun connectRemote_drivesRemoteConnecting_toConnected() = runTest {
        val vm = vm(StubRemoteConnectFeed())
        vm.start(); advanceUntilIdle()
        vm.selectHub(hubA)
        vm.connectRemote(); advanceUntilIdle()
        val s = assertIs<HubConnectUiState.RemoteConnecting>(vm.state.value)
        assertEquals(hubA, s.hub)
        assertEquals(RemoteConnState.CONNECTED, s.remote.conn)
        assertNull(s.remote.failure, "no failure on a clean connect")
    }

    @Test
    fun terminalFailure_isLostWithFailure_neverFakeConnected() = runTest {
        val vm = vm(StubRemoteConnectFeed.failing(RemoteFailure.AuthRejected))
        vm.start(); advanceUntilIdle(); vm.selectHub(hubA); vm.connectRemote(); advanceUntilIdle()
        val s = assertIs<HubConnectUiState.RemoteConnecting>(vm.state.value)
        assertEquals(RemoteConnState.LOST, s.remote.conn)
        assertEquals(RemoteFailure.AuthRejected, s.remote.failure)
    }

    @Test
    fun q5Switch_exactlyOneHub_newReplacesOld() = runTest {
        val vm = vm(StubRemoteConnectFeed())
        vm.start(); advanceUntilIdle(); vm.selectHub(hubA); vm.connectRemote(); advanceUntilIdle()
        assertEquals(hubA, assertIs<HubConnectUiState.RemoteConnecting>(vm.state.value).hub)

        // Q5: leave to the list (tears down hub-A's session) → pick hub-B → connect. Nothing carried across.
        vm.backToHubList(); advanceUntilIdle()
        assertIs<HubConnectUiState.HubList>(vm.state.value)
        vm.selectHub(hubB); vm.connectRemote(); advanceUntilIdle()
        val s = assertIs<HubConnectUiState.RemoteConnecting>(vm.state.value)
        assertEquals(hubB, s.hub, "exactly-one-hub: the new hub replaces the old")
        assertEquals(RemoteConnState.CONNECTED, s.remote.conn)
    }
}
