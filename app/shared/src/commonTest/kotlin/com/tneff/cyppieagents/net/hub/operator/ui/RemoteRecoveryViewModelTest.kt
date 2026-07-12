package com.tneff.cyppieagents.net.hub.operator.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-479 — the recovery-input flow honesty teeth (CYP-480 §3.2). A wrong/used code is **retryable** (error
 * kept, cleared on edit); **exhausted** is **terminal + fail-closed** (persists through edits, blocks further
 * submits — no phantom retry); a valid code **recovers** (onRecovered, no error); a blank code never reaches
 * the verifier. VM on an [UnconfinedTestDispatcher] (CYP-419 idiom).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteRecoveryViewModelTest {

    private fun scope(scheduler: TestCoroutineScheduler) =
        CoroutineScope(UnconfinedTestDispatcher(scheduler))

    @Test
    fun invalidCode_isRetryable_errorClearsOnEdit() = runTest {
        val vm = RemoteRecoveryViewModel(StubRecoveryCodeVerifier(RecoveryVerifyResult.Invalid), scope = scope(testScheduler))
        vm.onCodeChange("wrong-1"); vm.submit(); advanceUntilIdle()
        assertEquals(RecoveryError.InvalidCode, vm.state.value.error)
        assertFalse(vm.state.value.submitting)
        vm.onCodeChange("wrong-2") // retryable: editing clears the non-terminal error
        assertNull(vm.state.value.error)
    }

    @Test
    fun exhausted_isTerminal_failClosed_persistsAndBlocksResubmit() = runTest {
        var verifierCalls = 0
        val verifier = RecoveryCodeVerifier { verifierCalls++; RecoveryVerifyResult.Exhausted }
        val vm = RemoteRecoveryViewModel(verifier, scope = scope(testScheduler))
        vm.onCodeChange("last"); vm.submit(); advanceUntilIdle()
        assertEquals(RecoveryError.Exhausted, vm.state.value.error)
        assertTrue(vm.state.value.error!!.isTerminal)
        assertEquals(1, verifierCalls)
        // fail-closed: editing does NOT clear the terminal error, and a further submit is blocked (no re-verify).
        vm.onCodeChange("another")
        assertEquals(RecoveryError.Exhausted, vm.state.value.error, "terminal error must persist through edits")
        vm.submit(); advanceUntilIdle()
        assertEquals(1, verifierCalls, "no resubmit past exhausted (fail-closed)")
    }

    @Test
    fun acceptedCode_recovers_noError() = runTest {
        var recovered = false
        val vm = RemoteRecoveryViewModel(
            StubRecoveryCodeVerifier(RecoveryVerifyResult.Accepted),
            onRecovered = { recovered = true },
            scope = scope(testScheduler),
        )
        vm.onCodeChange("valid"); vm.submit(); advanceUntilIdle()
        assertTrue(recovered, "a valid code re-enrolls (re-pin) via onRecovered")
        assertNull(vm.state.value.error)
    }

    @Test
    fun blankCode_neverReachesVerifier() = runTest {
        var called = false
        val verifier = RecoveryCodeVerifier { called = true; RecoveryVerifyResult.Invalid }
        val vm = RemoteRecoveryViewModel(verifier, scope = scope(testScheduler))
        vm.submit(); advanceUntilIdle() // code is blank
        assertFalse(called, "a blank code must not reach the verifier")
        assertNull(vm.state.value.error)
    }
}
