package com.tneff.cyppieagents.firstrun

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * CYP-629 (Inc3, 3b) — the §7.3 clone-status poll. The SHARP invariant (PO): a running clone over ANY amount of time
 * NEVER becomes `CLONE_FAILED`. [isTerminalCloneStatus] is the only stop-path; there is no timeout/attempt-counter.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Cyp629FirstRunPollTest {

    private fun st(clone: CloneStatus) =
        FirstRunConfigStatus(loaded = true, apiKeySet = true, cloneStatus = clone)

    @Test
    fun runningClone_neverBecomesFailed_overManyPolls() = runTest {
        // A clone that never finishes: the source returns CLONING forever.
        val alwaysCloning = object : FirstRunConfigSource {
            override suspend fun status() = st(CloneStatus.CLONING)
        }
        val vm = FirstRunViewModel(alwaysCloning, pollIntervalMs = 1_000, scope = backgroundScope)
        // ~1000 poll intervals of virtual time.
        advanceTimeBy(1_000_000)
        runCurrent()
        // Still cloning — NEVER a fabricated failure. Mutation: add a timeout that flips to CLONE_FAILED → this reddens.
        assertEquals(CloneStatus.CLONING, vm.status.value.cloneStatus, "a long clone stays CLONING, never times out into a failure")
        assertNotEquals(CloneStatus.CLONE_FAILED, vm.status.value.cloneStatus)
    }

    @Test
    fun poll_stopsAtTerminal_reachesClonedOk() = runTest {
        // CLONING for the first two polls, then CLONED_OK (terminal).
        var calls = 0
        val transitions = object : FirstRunConfigSource {
            override suspend fun status(): FirstRunConfigStatus {
                calls++
                return if (calls >= 3) st(CloneStatus.CLONED_OK) else st(CloneStatus.CLONING)
            }
        }
        val vm = FirstRunViewModel(transitions, pollIntervalMs = 1_000, scope = backgroundScope)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(CloneStatus.CLONED_OK, vm.status.value.cloneStatus, "the poll transitions CLONING → CLONED_OK")
        val callsAtTerminal = calls
        // And it STOPS at terminal — no further fetches after CLONED_OK.
        advanceTimeBy(100_000)
        runCurrent()
        assertEquals(callsAtTerminal, calls, "the poll stops once terminal (isTerminalCloneStatus) — no extra fetches")
    }

    @Test
    fun notConfigured_doesNotSpin() = runTest {
        var calls = 0
        val notConfigured = object : FirstRunConfigSource {
            override suspend fun status(): FirstRunConfigStatus { calls++; return st(CloneStatus.NOT_CONFIGURED) }
        }
        FirstRunViewModel(notConfigured, pollIntervalMs = 1_000, scope = backgroundScope)
        advanceTimeBy(100_000)
        runCurrent()
        // NOT_CONFIGURED is not in progress → fetched once, no poll spin (no repo to clone yet).
        assertEquals(1, calls, "no repo set → poll never spins")
    }
}
