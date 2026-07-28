package com.tneff.cyppieagents.multihub

import com.tneff.cyppieagents.connect.ControlPlaneClient
import com.tneff.cyppieagents.connect.HubDescriptor
import com.tneff.cyppieagents.connect.HubRegistration
import com.tneff.cyppieagents.connect.StubControlPlaneClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-861 (Compose-M4 list-live) — the [ControlPlaneHubListSource] adapter delegates to the CP `hubs()`, and the
 * [hubListSourceFor] env-gate keeps prod byte-identical off-CP: a [StubControlPlaneClient] (the INERT off-CP
 * default) resolves to **null** → the CYP-856 `AgentShell` bar stays DORMANT; a real CP client → a live source.
 */
class ControlPlaneHubListSourceTest {

    private fun hub(id: String) =
        HubDescriptor(hubId = id, name = "Hub $id", online = true, defaultPort = 8787, lastSeen = 0L)

    private class FakeControlPlaneClient(private val list: List<HubDescriptor>) : ControlPlaneClient {
        override suspend fun hubs(): List<HubDescriptor> = list
        override suspend fun registerHub(name: String): HubRegistration = throw NotImplementedError()
    }

    @Test
    fun adapter_delegatesTo_controlPlane_hubs() = runTest {
        val list = listOf(hub("a"), hub("b"))
        assertEquals(list, ControlPlaneHubListSource(FakeControlPlaneClient(list)).hubs())
    }

    @Test
    fun hubListSourceFor_stubClient_isNull_soTheBarStaysDormant() {
        // The load-bearing byte-identical-off-CP gate: the INERT stub must NOT mount a live (spurious "Keine Hubs")
        // bar. Mutation: `hubListSourceFor` drops the `is StubControlPlaneClient` check → stub → non-null → RED.
        assertNull(
            hubListSourceFor(StubControlPlaneClient()),
            "the INERT StubControlPlaneClient (off-CP) must resolve to null → the bar stays dormant (prod byte-identical)",
        )
    }

    @Test
    fun hubListSourceFor_realClient_isLive_controlPlaneHubListSource() = runTest {
        val list = listOf(hub("a"))
        val source = hubListSourceFor(FakeControlPlaneClient(list))
        assertTrue(source is ControlPlaneHubListSource, "a real CP client → a live ControlPlaneHubListSource")
        assertEquals(list, source.hubs(), "the live source fetches the real registered-hub list")
    }
}
