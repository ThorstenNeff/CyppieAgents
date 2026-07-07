package com.tneff.cyppieagents.acl

import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.net.Backoff
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-296 defense-in-depth guard — the VM-core rejection `AclViewModel.toggle()` short-circuits on a revoked
 * session (`if (!s.editable || s.accessRevoked) return`). The UI already renders read-only chips, but a
 * *dispatched* toggle on a revoked session must ALSO be refused at the VM (no optimistic mutation, no PUT) — the
 * server 401 is the real enforcement, this keeps the client posture aligned. Without this test the `||
 * s.accessRevoked` clause is unpinned (a mutation reds nothing).
 *
 * Mutation proof: drop `|| s.accessRevoked` → the toggle proceeds → `setAcl` is called + a pending entry appears
 * → both asserts RED. (The `editable` half stays green — editable is still true here; only the revoke blocks it.)
 */
class AclRevokedToggleRejectedTest {

    /** Seeds a writable member cell (worker `frontend` in `po-frontend`) and COUNTS the write-through (PUT). */
    private class RecordingApi : AclApi {
        var setAclCalls = 0
        override suspend fun channels(): List<Channel> =
            listOf(Channel("po-frontend", "PO ↔ Frontend", ChannelKind.HUB, listOf("po", "frontend")))
        override suspend fun agents(): List<Agent> =
            listOf(Agent("po", "Product Owner", Role.PO, "po"), Agent("frontend", "Frontend", Role.WORKER, "frontend"))
        override suspend fun acl(channelId: String?, agentId: String?): List<AclEntry> =
            listOf(AclEntry("po-frontend", "frontend", canRead = true, canWrite = true))
        override suspend fun setAcl(entry: AclEntry): AclEntry { setAclCalls++; return entry }
    }

    /** Connected → AccessRevoked (terminal 1008) → the VM flips `accessRevoked` and cancels the collector. */
    private class RevokingSource : AclLiveSource {
        override fun events(): Flow<AclLiveEvent> = flow {
            emit(AclLiveEvent.Connected)
            emit(AclLiveEvent.AccessRevoked)
        }
    }

    @Test
    fun toggleOnRevokedSession_isRejected_noOptimisticMutation_noPut() = runTest {
        val api = RecordingApi()
        // editable defaults true → the ONLY thing blocking the toggle below is `accessRevoked` (isolates the clause).
        val vm = AclViewModel(api, RevokingSource(), backoff = Backoff(initialMs = 1, maxMs = 1), scope = backgroundScope)
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertTrue(vm.state.value.accessRevoked, "precondition: the terminal revoke is applied before the toggle")

        // A toggle that WOULD write on a live session (member worker cell → no PO-lockout, no self-blind).
        vm.toggleWrite("po-frontend", "frontend")
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()

        assertEquals(0, api.setAclCalls, "a dispatched toggle on a revoked session must NOT PUT (fail-closed core)")
        assertTrue(vm.state.value.pending.isEmpty(), "and must leave no optimistic pending entry")
    }
}
