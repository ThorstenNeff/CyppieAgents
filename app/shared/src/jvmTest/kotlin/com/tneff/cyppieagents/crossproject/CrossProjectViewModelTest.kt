package com.tneff.cyppieagents.crossproject

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-93 ViewModel contract: authorization is a deliberate **owner-consent** act, fail-closed without a
 * token, and the security invariant (§2.6) is structural — there is no "accept from message" path, only
 * [confirmAuthorize] behind the consent gate. Mutation-provable: drop the consent gate / the editable gate
 * → the matching test goes RED. The non-suspending stub settles synchronously under Unconfined.
 */
class CrossProjectViewModelTest {

    private val members = listOf(CrossMember("agent-b", "p2", CrossAccess.READ))

    private fun vm(editable: Boolean = true, shared: Boolean = false, deny: String? = null) = CrossProjectViewModel(
        StubCrossProjectRepository(
            reachByChannel = mapOf("c1" to members),
            denyWrites = deny,
            initiallyShared = if (shared) setOf("c1") else emptySet(),
        ),
        channelId = "c1",
        editable = editable,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun load_populatesStatus_withConcreteReach() {
        val vm = vm()
        assertTrue(vm.state.value.isCrossProject)
        assertEquals(listOf("agent-b"), vm.state.value.reachableMembers.map { it.agentId })
        assertFalse(vm.state.value.shared)
    }

    @Test
    fun authorize_requiresOwnerConsent_beforeConfirm() {
        val vm = vm()
        vm.openDialog()
        assertFalse(vm.state.value.canConfirm) // consent not given → confirm disabled
        vm.confirmAuthorize()
        assertFalse(vm.state.value.shared) // no-op without consent
        vm.setOwnerConsent(true)
        assertTrue(vm.state.value.canConfirm)
        vm.confirmAuthorize()
        assertTrue(vm.state.value.shared) // authorized only after the deliberate consent
        assertFalse(vm.state.value.dialogOpen)
    }

    @Test
    fun revoke_fallsBackToProjectLocal_immediately() {
        val vm = vm(shared = true)
        assertTrue(vm.state.value.shared)
        vm.revoke()
        assertFalse(vm.state.value.shared) // immediately fail-closed
    }

    @Test
    fun failClosed_withoutOperator_noAuthorizeNorRevoke() {
        val vm = vm(editable = false)
        vm.openDialog()
        assertFalse(vm.state.value.dialogOpen) // no-op
        val revokeVm = vm(editable = false, shared = true)
        revokeVm.revoke()
        assertTrue(revokeVm.state.value.shared) // revoke is a no-op without the gate
    }

    @Test
    fun serverGateError_mapsToOperatorRequired() {
        val vm = vm(deny = "operator_required")
        vm.openDialog(); vm.setOwnerConsent(true)
        vm.confirmAuthorize()
        assertEquals("crossproject_operator_required", vm.state.value.error)
    }
}
