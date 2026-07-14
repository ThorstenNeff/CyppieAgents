package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * F1 (post-login silent-swallow fix) — a RAW throw in the remote-connect drive must surface an honest terminal
 * **LOST**, NEVER strand the UI on the **RELAY_DIALING** spinner (the CYP-575 "uncaught error → endless progress
 * ring instead of an honest error" hang class).
 *
 * Before the fix, [HubConnectViewModel.connectRemoteInternal]'s `runScope.launch` wrapped the connect drive in a
 * `try/finally` with **no `catch`**, so a throw from `factory.create` / `session.start` / the combine flow escaped
 * the launch and left `_state` on `RELAY_DIALING` forever (green in unit tests, because the stubs emit
 * `RemoteFailure` states and never throw). The fix adds a `catch` that surfaces `LOST` and tears the half-built
 * session down; `CancellationException` stays the Q5 teardown path (rethrown).
 *
 * **Mutation (RED):** remove the `catch (e: Throwable)` branch → `_state` stays `RELAY_DIALING` → the `LOST`
 * assertion reddens. The stub-fed happy/failure paths (Cyp419HubConnectViewModelTest) still stay green either way,
 * which is exactly why this hang class slipped the unit net.
 */
class HubConnectViewModelConnectFaultTest {

    private val oneHub = listOf(
        HubDescriptor("hub-abc", "my-host", online = true, defaultPort = 8787, lastSeen = 1_700_000_000_000L),
    )

    @Test
    fun connectRemote_rawThrowInComponentsFactory_surfacesLost_notStuckSpinner() = runTest {
        // A live components factory that throws synchronously in create() — the class of raw fault (a crypto/transport
        // init error, an asset ctor throw, …) that is NOT modeled as a RemoteFailure and used to escape the launch.
        val throwingFactory = RemoteConnectComponentsFactory { _, _ -> throw RuntimeException("boom in create()") }
        val vm = HubConnectViewModel(
            controlPlane = StubControlPlaneClient(hubs = oneHub),
            credentials = StubHubCredentialRepository(),
            connectFeed = StubLocalConnectFeed(),
            remoteComponentsFactory = throwingFactory,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        vm.start()
        vm.selectHub(oneHub.first())   // → ChoosingMode (connectRemote's entry precondition)
        vm.connectRemote()             // drives the launch: factory.create throws → F1 catch → LOST

        val s = vm.state.value
        assertIs<HubConnectUiState.RemoteConnecting>(s)
        assertEquals(
            RemoteConnState.LOST,
            (s as HubConnectUiState.RemoteConnecting).remote.conn,
            "a raw connect-drive throw must land on honest LOST — never stay on the RELAY_DIALING spinner",
        )
    }

    /**
     * Assist Finding-1 (the cancel tooth) — a CANCELLED connect (Q5 hub-switch / teardown) must be RETHROWN, never
     * caught as a `Throwable` and painted `LOST` (which would falsely tell the operator the connection failed when
     * they simply switched away). Modeled by a feed whose collect raises `CancellationException` — the exact
     * exception a job-cancel propagates through the collect.
     *
     * **Mutation (RED):** remove `catch (CancellationException) { throw c }` in [HubConnectViewModel] → the
     * CancellationException falls to the `catch (Throwable)` branch → state is painted `LOST` → this assertion
     * reddens. Without this tooth that mutation stays green (the "green-in-unit, breaks-live" class).
     */
    @Test
    fun connectRemote_cancellation_isNotSurfacedAsLost() = runTest {
        val cancellingFeed = RemoteConnectFeed { flow<com.tneff.cyppieagents.net.hub.remote.RemoteSessionState> { throw CancellationException("hub-switch") } }
        val vm = HubConnectViewModel(
            controlPlane = StubControlPlaneClient(hubs = oneHub),
            credentials = StubHubCredentialRepository(),
            connectFeed = StubLocalConnectFeed(),
            remoteConnectFeed = cancellingFeed,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        vm.start()
        vm.selectHub(oneHub.first())
        vm.connectRemote()

        val s = vm.state.value
        assertIs<HubConnectUiState.RemoteConnecting>(s)
        assertEquals(
            RemoteConnState.RELAY_DIALING,
            (s as HubConnectUiState.RemoteConnecting).remote.conn,
            "a cancelled connect must NOT be painted LOST — it stays at the pre-cancel state (rethrown, not caught as Throwable)",
        )
    }
}
