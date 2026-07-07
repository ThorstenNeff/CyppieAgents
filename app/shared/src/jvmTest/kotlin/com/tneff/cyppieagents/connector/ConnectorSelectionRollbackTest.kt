package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.model.ConnectorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CYP-290 (Sweep-#4 Finding C) — the connector A-downgrade must not FAKE SUCCESS. `selectKind(STREAM_JSON)` sets
 * `draftKind` optimistically before the connector-endpoint write; on failure the picker previously kept showing
 * A while the server still had B. The fix rolls `draftKind` back to `initialKind` (the server's truth) on the
 * write failure — mirroring the B-path (confirmOptIn), which never sets the draft before the server confirms.
 *
 * Unconfined scope runs the write synchronously → assert on the settled state. Mutation proof: drop the
 * `draftKind = it.initialKind` rollback → [aDowngradeFailure_rollsBackDraftToInitial] REDs (draft stays A).
 */
class ConnectorSelectionRollbackTest {

    private fun editVm(repo: ConnectorSelectionRepository, initialKind: ConnectorKind) =
        ConnectorSelectionViewModel(
            repo,
            agentId = "frontend",
            editable = true,
            initialKind = initialKind, // an existing agent's current connector (edit context)
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

    @Test
    fun aDowngradeFailure_rollsBackDraftToInitial_noFakeSuccess() {
        // Existing B agent; the connector-endpoint write is denied.
        val vm = editVm(StubConnectorSelectionRepository(denyWrites = "operator_required"), ConnectorKind.MCP)
        vm.selectKind(ConnectorKind.STREAM_JSON) // optimistic draft → A, then activate() fails
        assertEquals(
            ConnectorKind.MCP,
            vm.state.value.draftKind,
            "a failed A-downgrade must roll the draft back to the server's B — never show A the server didn't accept",
        )
        assertNotNull(vm.state.value.error)
    }

    @Test
    fun aDowngradeSuccess_keepsDraftA() {
        // Non-vacuous contrast: a SUCCESSFUL downgrade keeps the draft at A (the rollback is failure-only).
        val vm = editVm(StubConnectorSelectionRepository(), ConnectorKind.MCP)
        vm.selectKind(ConnectorKind.STREAM_JSON)
        assertEquals(ConnectorKind.STREAM_JSON, vm.state.value.draftKind)
        assertNull(vm.state.value.error)
    }
}
