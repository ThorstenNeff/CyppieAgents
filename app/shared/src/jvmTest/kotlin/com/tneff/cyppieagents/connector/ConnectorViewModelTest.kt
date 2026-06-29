package com.tneff.cyppieagents.connector

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-123 ViewModel contract — the load-bearing security invariants of the connector surface:
 *  - B (MCP) is **never** silently selected: a new agent seeds A, and reaching B costs a deliberate pick + ack;
 *  - confirm is **fail-closed**: blocked without the operator gate and (for B) without the risk acknowledgment;
 *  - changing the kind **resets** the acknowledgment (no stale ack carries B through).
 * Each is mutation-provable: drop the guard → the matching assert goes RED. The non-suspending stub settles
 * synchronously under Unconfined.
 */
class ConnectorViewModelTest {

    private val streamAgent = AgentConnectorInfo("a-stream", ConnectorKind.STREAM_JSON, defaultCapabilitiesFor(ConnectorKind.STREAM_JSON))
    private val mcpAgent = AgentConnectorInfo("a-mcp", ConnectorKind.MCP, defaultCapabilitiesFor(ConnectorKind.MCP))

    private fun vm(editable: Boolean = true, deny: String? = null) = ConnectorViewModel(
        StubConnectorRepository(
            initial = mapOf(streamAgent.agentId to streamAgent, mcpAgent.agentId to mcpAgent),
            denyWrites = deny,
        ),
        editable = editable,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun load_populatesPerAgentInfos() {
        val vm = vm()
        assertEquals(ConnectorKind.STREAM_JSON, vm.state.value.infoFor("a-stream")?.kind)
        assertEquals(ConnectorKind.MCP, vm.state.value.infoFor("a-mcp")?.kind)
        assertNull(vm.state.value.infoFor("nope"), "fail-closed: unknown agent → no faked info")
    }

    @Test
    fun openSelect_seedsCurrentKind_bNeverPreselectedForNewAgent() {
        val vm = vm()
        vm.openSelect("brand-new") // no info → must default to A, never B
        assertEquals(ConnectorKind.STREAM_JSON, vm.state.value.draftKind, "B is never the default for a new agent")
        assertFalse(vm.state.value.riskAcknowledged, "ack starts unchecked")
    }

    @Test
    fun openSelect_notEditable_isNoOp_failClosed() {
        val vm = vm(editable = false)
        vm.openSelect("a-stream")
        assertFalse(vm.state.value.dialogOpen, "operator gate: no dialog without the operator token")
    }

    @Test
    fun confirm_mcpWithoutAck_isBlocked_failClosed() {
        val vm = vm()
        vm.openSelect("a-stream")
        vm.setKind(ConnectorKind.MCP)
        assertFalse(vm.state.value.canConfirm, "B without the ack → confirm disabled")
        vm.confirmSelection()
        // no write happened: the agent stays on A and the dialog stays open.
        assertEquals(ConnectorKind.STREAM_JSON, vm.state.value.infoFor("a-stream")?.kind)
        assertTrue(vm.state.value.dialogOpen)
    }

    @Test
    fun confirm_mcpWithAck_writesB() {
        val vm = vm()
        vm.openSelect("a-stream")
        vm.setKind(ConnectorKind.MCP)
        vm.setRiskAcknowledged(true)
        assertTrue(vm.state.value.canConfirm)
        vm.confirmSelection()
        assertEquals(ConnectorKind.MCP, vm.state.value.infoFor("a-stream")?.kind, "deliberate B opt-in is applied")
        assertFalse(vm.state.value.dialogOpen, "dialog closes on success")
    }

    @Test
    fun confirm_streamJson_needsNoAck() {
        val vm = vm()
        vm.openSelect("a-mcp") // currently B
        vm.setKind(ConnectorKind.STREAM_JSON)
        assertTrue(vm.state.value.canConfirm, "switching to A needs no acknowledgment")
        vm.confirmSelection()
        assertEquals(ConnectorKind.STREAM_JSON, vm.state.value.infoFor("a-mcp")?.kind)
    }

    @Test
    fun setKind_resetsAcknowledgment() {
        val vm = vm()
        vm.openSelect("a-stream")
        vm.setKind(ConnectorKind.MCP)
        vm.setRiskAcknowledged(true)
        vm.setKind(ConnectorKind.STREAM_JSON)
        vm.setKind(ConnectorKind.MCP) // back to B: the prior ack must NOT carry over
        assertFalse(vm.state.value.riskAcknowledged, "changing the kind clears a stale ack")
        assertFalse(vm.state.value.canConfirm)
    }

    @Test
    fun confirm_serverGateDenied_surfacesError_noWrite() {
        val vm = vm(deny = "operator_required")
        vm.openSelect("a-stream")
        vm.setKind(ConnectorKind.STREAM_JSON)
        vm.confirmSelection()
        assertEquals("connector_operator_required", vm.state.value.error, "server gate → honest error key")
        assertEquals(ConnectorKind.STREAM_JSON, vm.state.value.infoFor("a-stream")?.kind, "no client-side write past the gate")
        assertTrue(vm.state.value.dialogOpen, "dialog stays open so the operator sees the reason")
    }
}
