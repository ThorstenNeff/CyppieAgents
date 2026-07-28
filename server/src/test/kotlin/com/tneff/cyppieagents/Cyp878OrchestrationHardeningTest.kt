package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.ChannelMemberGrant
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * CYP-878 (OS-F hardening) — the reserved-spoke-namespace integrity fix. `createChannel` (CYP-869) and `addAgent`
 * share the `po-*` / `op-po` hub-and-spoke id space; without a guard an operator can mint `po-X` before worker X is
 * added, or a worker's spoke can be double-appended, and `spokeChannelFor.firstOrNull` then resolves to whichever
 * landed first = a SILENT topology/ACL collision. Fail-closed both sides. Each tooth is red-provable vs a named mutant.
 */
class Cyp878OrchestrationHardeningTest {
    private fun newState() = HubState.hubAndSpoke(
        listOf(Agent("po", "PO", Role.PO, "po"), Agent("backend", "BE", Role.WORKER, "backend")),
        HubState.OPERATOR_ID,
    )

    // Fail-closed: createChannel rejects the reserved worker-spoke prefix `po-*`.
    // Mutant: drop the reserved-id guard → `po-x` is created → later addAgent("x") collides → reds.
    @Test fun createRejectsReservedSpokePrefix() {
        assertFailsWith<BadRequestException>("po-* is the reserved spoke namespace") {
            newState().createChannel("po-x", "X", ChannelKind.GROUP, listOf(ChannelMemberGrant("backend", true, true)))
        }
    }

    // Fail-closed: createChannel rejects the reserved operator channel id `op-po` (OP_PO_CHANNEL_ID).
    // Mutant: drop the `id == OP_PO_CHANNEL_ID` arm → `op-po` is created → collides with the PO's op-po → reds.
    @Test fun createRejectsReservedOpPoId() {
        assertFailsWith<BadRequestException>("op-po is reserved") {
            newState().createChannel("op-po", "X", ChannelKind.DIRECT, listOf(ChannelMemberGrant("backend", true, true)))
        }
    }

    // ★ addAgent dup-guard: re-adding the SAME worker must NOT mint a second `po-<id>` spoke.
    // Mutant: drop the `channels.none { po-<id> }` guard → a second `po-newbie` channel appears → reds
    // (spokeChannelFor.firstOrNull would then silently pick one of two colliding spokes).
    @Test fun addAgentDoesNotDoubleAppendWorkerSpoke() {
        val state = newState()
        val newbie = Agent("newbie", "NB", Role.WORKER, "newbie")
        state.addAgent(newbie)
        state.addAgent(newbie) // re-add (double-add / rehydration)
        assertEquals(1, state.channels.count { it.id == "po-newbie" }, "exactly ONE po-newbie spoke — no silent second")
    }

    // Integrity: with the reserved guard, the operator CANNOT pre-seed `po-x`, so a later addAgent("x") owns the
    // ONLY `po-x` spoke — the collision path is closed end-to-end.
    @Test fun reservedGuard_leavesAddAgentSoleOwnerOfSpoke() {
        val state = newState()
        assertFailsWith<BadRequestException> { state.createChannel("po-x", "hijack", ChannelKind.GROUP, emptyList()) }
        state.addAgent(Agent("x", "X", Role.WORKER, "x"))
        assertEquals(1, state.channels.count { it.id == "po-x" }, "addAgent owns the sole po-x spoke (no operator pre-seed)")
    }
}
