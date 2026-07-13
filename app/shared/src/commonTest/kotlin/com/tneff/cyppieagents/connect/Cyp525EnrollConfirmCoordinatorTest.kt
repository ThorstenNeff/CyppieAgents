package com.tneff.cyppieagents.connect

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-525 §2 — the [LiveEnrollConfirmCoordinator] bridge: [confirmSavedCodes] publishes [EnrollConfirmState.Revealing]
 * (which the ViewModel surfaces as `RevealCodes`) and SUSPENDS until the operator's [confirmSaved] (→ `true` ⇒ the
 * session sends `SavedAck`) or [abort] (→ `false` ⇒ fail-closed, no `SavedAck`); either way it returns to Idle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Cyp525EnrollConfirmCoordinatorTest {

    private val codes = List(10) { "code-$it" }

    @Test
    fun confirmSavedCodes_reveals_thenConfirm_returnsTrue_backToIdle() = runTest {
        val c = LiveEnrollConfirmCoordinator()
        val job = async { c.confirmSavedCodes(codes) }
        advanceUntilIdle()
        assertEquals(EnrollConfirmState.Revealing(codes), c.state.value, "confirmSavedCodes surfaces the reveal + suspends")
        c.confirmSaved()
        assertTrue(job.await(), "confirmSaved ⇒ true (⇒ SavedAck)")
        assertEquals(EnrollConfirmState.Idle, c.state.value, "returns to Idle after")
    }

    @Test
    fun abort_returnsFalse_failClosed_backToIdle() = runTest {
        val c = LiveEnrollConfirmCoordinator()
        val job = async { c.confirmSavedCodes(codes) }
        advanceUntilIdle()
        c.abort()
        assertFalse(job.await(), "abort ⇒ false (fail-closed, no SavedAck)")
        assertEquals(EnrollConfirmState.Idle, c.state.value)
    }
}
