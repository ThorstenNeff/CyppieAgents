package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.model.ProviderInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-123/137 display-VM contract: the single read load populates the per-agent caps AND the per-agent provider
 * from one snapshot; an unknown agent is `null` (fail-closed — never a faked "full" default / never an invented
 * provider), and the panel open/close toggle is honest. The non-suspending stub settles synchronously under
 * [Dispatchers.Unconfined], so the `init` reload is visible immediately.
 */
class ConnectorCapabilityViewModelTest {

    private val streamCaps = defaultCapabilitiesFor(com.tneff.cyppieagents.model.ConnectorKind.STREAM_JSON)
    private val mcpCaps = defaultCapabilitiesFor(com.tneff.cyppieagents.model.ConnectorKind.MCP)

    private fun vm() = ConnectorCapabilityViewModel(
        StubConnectorCapabilityRepository(
            initial = mapOf("a-stream" to streamCaps, "a-mcp" to mcpCaps),
            // Deliberately: "a-stream" has a provider, "a-mcp" does NOT — provider absence is independent of
            // caps presence (an agent can have caps but no reported provider ⇒ fail-closed, no invented one).
            providers = mapOf("a-stream" to ProviderInfo.CLAUDE),
        ),
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun load_populatesCapabilitiesFor() {
        val vm = vm()
        assertEquals(streamCaps, vm.capabilitiesFor("a-stream"))
        assertEquals(mcpCaps, vm.capabilitiesFor("a-mcp"))
    }

    @Test
    fun load_populatesProviderFor() {
        assertEquals(ProviderInfo.CLAUDE, vm().providerFor("a-stream"))
    }

    @Test
    fun unknownAgent_isNull_failClosed() {
        assertNull(vm().capabilitiesFor("nope"), "fail-closed: unknown agent → no faked caps")
    }

    @Test
    fun providerAbsent_isNull_failClosed_independentOfCaps() {
        // "a-mcp" has caps but no reported provider → providerFor is null (no phantom "Claude"); "nope" is
        // unknown entirely → also null. Provider is never invented from caps presence.
        assertNull(vm().providerFor("a-mcp"), "fail-closed: caps present but provider unreported → no phantom")
        assertNull(vm().providerFor("nope"), "fail-closed: unknown agent → no invented provider")
    }

    @Test
    fun openAndClosePanel_togglesOpenPanelAgentId() {
        val vm = vm()
        assertNull(vm.state.value.openPanelAgentId, "no panel open initially")
        vm.openPanel("a-mcp")
        assertEquals("a-mcp", vm.state.value.openPanelAgentId)
        vm.closePanel()
        assertNull(vm.state.value.openPanelAgentId)
    }
}
