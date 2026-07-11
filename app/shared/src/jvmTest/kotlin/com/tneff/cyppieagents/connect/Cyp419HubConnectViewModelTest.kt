package com.tneff.cyppieagents.connect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CYP-419 (S-L) — the hubConnect flow's **honesty logic**, tested against the stub `connect/` repos (no tags,
 * no render). Pins: routing (empty ⇒ Seq A / non-empty ⇒ Seq B / CP-unreachable ⇒ H5), the Q5 credential gate
 * (INVALID blocks, UNREACHABLE proceeds with WARN), and — the load-bearing one — that the connect failure **cause
 * comes from the feed, never guessed** by the client. The VM runs on an [UnconfinedTestDispatcher] so its launches
 * (and the stub feed's flow) complete eagerly and the state is settled at the assertion.
 */
class Cyp419HubConnectViewModelTest {

    private fun TestScope.newVm(
        cp: ControlPlaneClient = StubControlPlaneClient(),
        creds: HubCredentialRepository = StubHubCredentialRepository(),
        feed: LocalConnectFeed = StubLocalConnectFeed(),
    ) = HubConnectViewModel(
        cp, creds, feed, defaultHubName = "host-1",
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
    )

    private val oneHub = listOf(HubDescriptor("hub-abc", "my-host", online = true, defaultPort = 8787, lastSeen = 1_700_000_000_000L))

    @Test
    fun start_emptyHubs_routesToRegister() = runTest {
        val m = newVm(cp = StubControlPlaneClient(hubs = emptyList()))
        m.start()
        assertIs<HubConnectUiState.Register>(m.state.value)
    }

    @Test
    fun start_withHubs_routesToHubList() = runTest {
        val m = newVm(cp = StubControlPlaneClient(hubs = oneHub))
        m.start()
        assertEquals(oneHub, (m.state.value as HubConnectUiState.HubList).hubs)
    }

    @Test
    fun start_cpUnreachable_isHonestError_notAHang() = runTest {
        val m = newVm(cp = StubControlPlaneClient(unreachable = true))
        m.start()
        assertEquals(HubConnectUiState.HubsUnreachable, m.state.value)
    }

    @Test
    fun register_success_advancesToCredentials() = runTest {
        val m = newVm(cp = StubControlPlaneClient(hubs = emptyList()))
        m.start()
        m.register()
        assertIs<HubConnectUiState.Credentials>(m.state.value)
    }

    @Test
    fun register_failure_showsOfflineErrorPhase() = runTest {
        val m = newVm(cp = StubControlPlaneClient(hubs = emptyList(), registerFails = true))
        m.start()
        assertIs<HubConnectUiState.Register>(m.state.value)
        m.register()
        assertEquals(RegisterPhase.ERROR, (m.state.value as HubConnectUiState.Register).phase)
    }

    @Test
    fun credentialGate_invalidBlocks_validatedAndUnreachableProceed() = runTest {
        // INVALID → continueToReady blocked (stays on Credentials).
        val invalid = newVm(cp = StubControlPlaneClient(hubs = emptyList()), creds = StubHubCredentialRepository(CredentialValidation.INVALID))
        invalid.start(); invalid.register(); invalid.submitCredential("sk-ant-bad")
        assertEquals(CredentialPhase.INVALID, (invalid.state.value as HubConnectUiState.Credentials).phase)
        assertFalse(invalid.continueToReady(), "Q5: INVALID must block the transition to Ready")
        assertIs<HubConnectUiState.Credentials>(invalid.state.value)

        // UNREACHABLE → may proceed (WARN — the key may be valid, Anthropic transiently down).
        val unreachable = newVm(cp = StubControlPlaneClient(hubs = emptyList()), creds = StubHubCredentialRepository(CredentialValidation.UNREACHABLE))
        unreachable.start(); unreachable.register(); unreachable.submitCredential("sk-ant-x")
        assertTrue(unreachable.continueToReady(), "Q5: UNREACHABLE may proceed with WARN")
        assertEquals(HubConnectUiState.Ready, unreachable.state.value)
    }

    @Test
    fun connect_failureCause_comesFromTheFeed_notGuessed() = runTest {
        // The feed reports PORT_UNREACHABLE; the VM must surface exactly that (never a hedged/guessed cause).
        val m = newVm(cp = StubControlPlaneClient(hubs = oneHub), feed = StubLocalConnectFeed.failing(ConnectCause.PORT_UNREACHABLE))
        m.start()
        m.selectHub(oneHub.first())
        m.connectLocal()
        assertEquals(ConnectProgress.Failed(ConnectCause.PORT_UNREACHABLE), (m.state.value as HubConnectUiState.Connecting).progress)
    }

    @Test
    fun connect_happyPath_reachesConnectedAtTheEnd() = runTest {
        val m = newVm(cp = StubControlPlaneClient(hubs = oneHub), feed = StubLocalConnectFeed())
        m.start()
        m.selectHub(oneHub.first())
        m.connectLocal()
        assertEquals(ConnectProgress.Connected, (m.state.value as HubConnectUiState.Connecting).progress)
    }
}
