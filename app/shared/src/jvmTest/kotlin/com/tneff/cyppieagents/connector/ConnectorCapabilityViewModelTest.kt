package com.tneff.cyppieagents.connector

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-123 display-VM contract: the read load populates the per-agent caps, an unknown agent is `null`
 * (fail-closed — never a faked "full" default), and the panel open/close toggle is honest. The non-suspending
 * stub settles synchronously under [Dispatchers.Unconfined], so the `init` reload is visible immediately.
 */
class ConnectorCapabilityViewModelTest {

    private val streamCaps = defaultCapabilitiesFor(com.tneff.cyppieagents.model.ConnectorKind.STREAM_JSON)
    private val mcpCaps = defaultCapabilitiesFor(com.tneff.cyppieagents.model.ConnectorKind.MCP)

    private fun vm() = ConnectorCapabilityViewModel(
        StubConnectorCapabilityRepository(mapOf("a-stream" to streamCaps, "a-mcp" to mcpCaps)),
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun load_populatesCapabilitiesFor() {
        val vm = vm()
        assertEquals(streamCaps, vm.capabilitiesFor("a-stream"))
        assertEquals(mcpCaps, vm.capabilitiesFor("a-mcp"))
    }

    @Test
    fun unknownAgent_isNull_failClosed() {
        assertNull(vm().capabilitiesFor("nope"), "fail-closed: unknown agent → no faked caps")
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
