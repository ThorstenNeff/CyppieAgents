package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.model.ConnectorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-123 Inc 2 (spec §3) — the connector-selection security state machine. Connector A is the first-class
 * default; **B (MCP) is an ack-gated, operator-gated, human-only opt-in** — the only path is
 * [ConnectorSelectionViewModel.confirmOptIn] behind [ConnectorSelectionUiState.canConfirm]. Mutation-provable:
 * drop the ack gate / the editable gate / pre-select B → the matching test goes RED. The non-suspending stub
 * settles synchronously under Unconfined.
 */
class ConnectorSelectionViewModelTest {

    private fun vm(editable: Boolean = true, deny: String? = null): Pair<ConnectorSelectionViewModel, StubConnectorSelectionRepository> {
        val repo = StubConnectorSelectionRepository(denyWrites = deny)
        val vm = ConnectorSelectionViewModel(
            repo,
            agentId = "frontend",
            editable = editable,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        return vm to repo
    }

    /** S1: B is never the initial/settled connector — A is the default. (Mutation: default MCP → RED.) */
    @Test
    fun s1_bNeverPreselected_draftIsStreamJsonInitially() {
        val (vm, _) = vm()
        assertEquals(ConnectorKind.STREAM_JSON, vm.state.value.draftKind)
        assertFalse(vm.state.value.optInDialogOpen)
    }

    /** S2: selecting B opens the dialog but does NOT activate without the ack — confirm is a no-op, draft stays A. */
    @Test
    fun s2_ackGatedConfirm_noActivationWithoutAck() {
        val (vm, repo) = vm()
        vm.selectKind(ConnectorKind.MCP)
        assertTrue(vm.state.value.optInDialogOpen)
        assertFalse(vm.state.value.canConfirm) // no ack yet
        vm.confirmOptIn()
        // No B activation recorded, draft still A, dialog still open (fail-closed).
        assertFalse(repo.activations.any { it.second.kind == ConnectorKind.MCP })
        assertEquals(ConnectorKind.STREAM_JSON, vm.state.value.draftKind)
        assertTrue(vm.state.value.optInDialogOpen)
    }

    /** S3: with the ack, confirm records exactly one acknowledged MCP activation and closes the dialog. */
    @Test
    fun s3_ackThenConfirm_activatesBAndCloses() {
        val (vm, repo) = vm()
        vm.selectKind(ConnectorKind.MCP)
        vm.setRiskAcknowledged(true)
        assertTrue(vm.state.value.canConfirm)
        vm.confirmOptIn()
        val mcp = repo.activations.filter { it.second.kind == ConnectorKind.MCP }
        assertEquals(1, mcp.size)
        assertTrue(mcp.single().second.riskAcknowledged)
        assertEquals(ConnectorKind.MCP, vm.state.value.draftKind)
        assertFalse(vm.state.value.optInDialogOpen)
        assertFalse(vm.state.value.riskAcknowledged) // reset
    }

    /** S4: selecting A activates immediately and never opens the opt-in dialog. */
    @Test
    fun s4_aImmediate_noDialog() {
        val (vm, repo) = vm()
        vm.selectKind(ConnectorKind.STREAM_JSON)
        assertFalse(vm.state.value.optInDialogOpen)
        val streamJson = repo.activations.filter { it.second.kind == ConnectorKind.STREAM_JSON }
        assertEquals(1, streamJson.size)
        assertEquals(ConnectorKind.STREAM_JSON, vm.state.value.draftKind)
    }

    /** S5: without the operator gate, confirm is a no-op even if the ack was somehow set. (Mutation: drop gate → RED.) */
    @Test
    fun s5_operatorGateFailClosed_noActivation() {
        val (vm, repo) = vm(editable = false)
        // selectKind is also gated, but force the worst case: ack set, then confirm.
        vm.setRiskAcknowledged(true)
        assertFalse(vm.state.value.canConfirm) // editable=false ⇒ never confirmable
        vm.confirmOptIn()
        assertTrue(repo.activations.isEmpty())
        assertEquals(ConnectorKind.STREAM_JSON, vm.state.value.draftKind)
    }

    /**
     * S7: anti-injection structural — the ONLY way to reach an MCP activation is confirmOptIn-with-ack. There is
     * no method that activates the connector from message/agent/external input (see the VM API: only selectKind
     * + confirmOptIn). A non-acknowledged write is rejected by the repository (server re-checks the ack).
     */
    @Test
    fun s7_onlyConfirmWithAckReachesMcpActivation() {
        val (vm, repo) = vm()
        // Every other entry point: selecting A, opening B without ack, cancelling — none yields an MCP activation.
        vm.selectKind(ConnectorKind.STREAM_JSON)
        vm.selectKind(ConnectorKind.MCP)
        vm.confirmOptIn() // no ack
        vm.cancelOptIn()
        assertFalse(repo.activations.any { it.second.kind == ConnectorKind.MCP })
        // The single sanctioned path:
        vm.selectKind(ConnectorKind.MCP)
        vm.setRiskAcknowledged(true)
        vm.confirmOptIn()
        assertEquals(1, repo.activations.count { it.second.kind == ConnectorKind.MCP })
        // And every recorded MCP activation is acknowledged (the stub/server reject an unacked B).
        assertTrue(repo.activations.filter { it.second.kind == ConnectorKind.MCP }.all { it.second.riskAcknowledged })
    }

    /** A server/stub gate rejection on confirm surfaces the dialog error key (and leaves the draft at A). */
    @Test
    fun serverGateError_mapsToOptInError() {
        val (vm, _) = vm(deny = "operator_required")
        vm.selectKind(ConnectorKind.MCP)
        vm.setRiskAcknowledged(true)
        vm.confirmOptIn()
        assertEquals("connector_optin_error", vm.state.value.error)
        assertEquals(ConnectorKind.STREAM_JSON, vm.state.value.draftKind) // no half-activation
        assertTrue(vm.state.value.optInDialogOpen) // stays open to show the error
    }

    /**
     * CYP-126 [Mittel]: an existing **B** agent opened in edit (initialKind = MCP) shows B as the settled draft
     * — proving a B agent is NOT silently reset to A. This is the **truth** being reflected, not a fresh opt-in:
     * no activation is recorded and no "changed" restart hint fires (draft == initial). [confirmOptIn] is still
     * the only path that *changes* to B. (Mutation: ignore initialKind / hardcode STREAM_JSON → RED.)
     */
    @Test
    fun cyp126_existingBAgentInEdit_draftInitsToMcp_notResetToA() {
        val repo = StubConnectorSelectionRepository()
        val vm = ConnectorSelectionViewModel(
            repo, agentId = "frontend", editable = true,
            initialKind = ConnectorKind.MCP,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        assertEquals(ConnectorKind.MCP, vm.state.value.draftKind)
        assertEquals(ConnectorKind.MCP, vm.state.value.initialKind)
        assertFalse(vm.state.value.optInDialogOpen)
        assertTrue(repo.activations.isEmpty()) // truth reflected, no fresh opt-in/activation
        assertFalse(vm.state.value.showEffectHint) // draft == initial ⇒ nothing changed yet
    }

    /**
     * CYP-126 ADD context: the create carries the connector (NewAgentSpec) — B at creation STILL goes through the
     * ack-gated opt-in, but the settled kind is captured via the callback with **no** connector-endpoint call,
     * and the edit-only "restart to apply" hint is suppressed (fresh spawn).
     */
    @Test
    fun cyp126_addContext_bGoesThroughAck_capturedViaCallback_noEndpointCall() {
        val repo = StubConnectorSelectionRepository()
        val chosen = mutableListOf<ConnectorKind>()
        val vm = ConnectorSelectionViewModel(
            repo, agentId = null, editable = true,
            onKindChosen = { chosen.add(it) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        vm.selectKind(ConnectorKind.MCP)
        assertTrue(vm.state.value.optInDialogOpen)
        vm.confirmOptIn() // no ack → fail-closed: nothing captured
        assertTrue(chosen.none { it == ConnectorKind.MCP })
        vm.setRiskAcknowledged(true)
        vm.confirmOptIn()
        assertEquals(ConnectorKind.MCP, chosen.last())
        assertEquals(ConnectorKind.MCP, vm.state.value.draftKind)
        assertTrue(repo.activations.isEmpty()) // add never calls the connector endpoint (the create carries it)
        assertFalse(vm.state.value.showEffectHint) // no restart hint in add context (fresh spawn)
    }

    /** CYP-126: in edit context, changing an existing A agent to B surfaces the "saved ≠ active — restart" hint. */
    @Test
    fun cyp126_editContext_changeFromInitial_showsEffectHint() {
        val (vm, _) = vm()
        assertFalse(vm.state.value.showEffectHint) // initialKind = STREAM_JSON, draft = STREAM_JSON
        vm.selectKind(ConnectorKind.MCP)
        vm.setRiskAcknowledged(true)
        vm.confirmOptIn()
        assertEquals(ConnectorKind.MCP, vm.state.value.draftKind)
        assertTrue(vm.state.value.showEffectHint) // draft (MCP) != initial (STREAM_JSON)
    }

    /** cancelOptIn closes the dialog, resets the ack, and leaves the draft at A. */
    @Test
    fun cancel_leavesDraftA() {
        val (vm, repo) = vm()
        vm.selectKind(ConnectorKind.MCP)
        vm.setRiskAcknowledged(true)
        vm.cancelOptIn()
        assertFalse(vm.state.value.optInDialogOpen)
        assertFalse(vm.state.value.riskAcknowledged)
        assertEquals(ConnectorKind.STREAM_JSON, vm.state.value.draftKind)
        assertNull(vm.state.value.error)
        assertFalse(repo.activations.any { it.second.kind == ConnectorKind.MCP })
    }
}
