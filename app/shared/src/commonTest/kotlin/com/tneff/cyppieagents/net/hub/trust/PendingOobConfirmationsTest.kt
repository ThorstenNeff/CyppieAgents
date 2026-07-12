package com.tneff.cyppieagents.net.hub.trust

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-478 — the OOB-confirm state holder that the UIUX screen drives. Verifies the request/approve/reject
 * machine: an in-flight confirmation surfaces as [OobConfirmState.Awaiting]; approve lets the awaiter proceed
 * (→ Idle, TOFU adopts downstream); reject throws [TrustConfirmationRejectedException] and leaves a terminal
 * [OobConfirmState.Rejected] (fail-closed — the caller never pins). Uses `UnconfinedTestDispatcher` so the
 * launched awaiter runs to its suspension point eagerly (the CYP-419 idiom).
 */
class PendingOobConfirmationsTest {

    @Test
    fun awaiting_thenApprove_completesAndReturnsToIdle() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val confirmer = PendingOobConfirmations()
        var succeeded = false

        val job = scope.launch { confirmer.awaitConfirmation("hub-1", "ab:cd"); succeeded = true }
        assertEquals(OobConfirmState.Awaiting("hub-1", "ab:cd"), confirmer.state.value)

        confirmer.approve()
        job.join()
        assertTrue(succeeded, "an approved confirmation must let awaitConfirmation return")
        assertEquals(OobConfirmState.Idle, confirmer.state.value)
        scope.cancel()
    }

    @Test
    fun awaiting_thenReject_throwsAndGoesRejected() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val confirmer = PendingOobConfirmations()
        var thrown: Throwable? = null

        val job = scope.launch {
            try {
                confirmer.awaitConfirmation("hub-1", "ab:cd")
            } catch (e: Throwable) {
                thrown = e
            }
        }
        assertEquals(OobConfirmState.Awaiting("hub-1", "ab:cd"), confirmer.state.value)

        confirmer.reject()
        job.join()
        assertTrue(thrown is TrustConfirmationRejectedException, "a reject must throw the fail-closed cancellation")
        assertEquals(OobConfirmState.Rejected("hub-1"), confirmer.state.value)
        scope.cancel()
    }

    @Test
    fun idleByDefault_noPendingConfirmation() {
        assertEquals(OobConfirmState.Idle, PendingOobConfirmations().state.value)
        assertNull((PendingOobConfirmations().state.value as? OobConfirmState.Awaiting))
    }
}
