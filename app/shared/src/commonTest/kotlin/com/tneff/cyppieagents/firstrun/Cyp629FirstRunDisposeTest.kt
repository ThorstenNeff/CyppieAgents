package com.tneff.cyppieagents.firstrun

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-629 (same class as CYP-443 Slice 3, [[cmp-remember-vm-no-oncleared]]) — [FirstRunViewModel.dispose] cancels
 * the §7.3 clone poll on composition-disposal. The gate holds the VM via a plain `remember` (no `ViewModelStore`),
 * so `onCleared` never fires in prod; without `dispose()` the `while (in-progress) delay()` poll loop keeps
 * re-fetching forever after the gate leaves composition (logout / auth-subtree teardown). Virtual-time idiom
 * mirrors [Cyp629FirstRunPollTest]. Headless-safe (no Compose render).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Cyp629FirstRunDisposeTest {

    private fun cloning() = FirstRunConfigStatus(loaded = true, apiKeySet = true, cloneStatus = CloneStatus.CLONING)

    @Test
    fun dispose_cancelsThePollLoop_noFetchesAfterTeardown() = runTest {
        var calls = 0
        val alwaysCloning = object : FirstRunConfigSource {
            override suspend fun status(): FirstRunConfigStatus { calls++; return cloning() }
        }
        val vm = FirstRunViewModel(alwaysCloning, pollIntervalMs = 1_000, scope = backgroundScope)
        advanceTimeBy(3_500); runCurrent() // initial fetch + ~3 poll cycles
        val before = calls
        assertTrue(before >= 2, "precondition: the §7.3 poll is actively re-fetching a still-cloning source")

        vm.dispose()
        advanceTimeBy(20_000); runCurrent() // 20 more intervals of virtual time

        assertEquals(
            before, calls,
            "CYP-629 H-1 hygiene: dispose() cancels the clone-poll loop — zero fetches after teardown (never a " +
                "forever-poll leak when the plain-remember VM leaves composition with no onCleared)",
        )
    }
}
