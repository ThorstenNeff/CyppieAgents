package com.tneff.cyppieagents.auth

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-179 (stage-2) — the constant-time **floor** teeth. Models the Aiven-Postgres reality: the existence check
 * is SLOW for a found email (hydration round-trips) and FAST for a miss (short-circuit). Two invariants:
 *  1. both branches pad UP to the floor (the fast/miss branch is padded too — a `floorMs=0` mutation reds it);
 *  2. **the cap IS the floor** — a check that would run PAST the floor is cut to a **uniform 503 at ~floor**,
 *     NEVER a slow-200 above the floor (the tail leak Test caught: a cap ≫ floor let a found check return a
 *     slow-200 in the (floor, cap) band → found/miss tell survived). The leak-region test below reds if the cap
 *     is ever moved back above the floor.
 */
class RegisterFloorTest {

    private val FLOOR = 80L
    private val FOUND_CHECK_MS = 25L // a "found" existence check that is slow but still UNDER the floor

    /** identityExists: slow-by-[checkDelayMs] for a seeded email, fast otherwise; optional throw to model outage. */
    private class TimedBackend(
        private val existing: Set<String>,
        private val checkDelayMs: Long,
        private val throwOnCheck: Boolean = false,
    ) : KratosRegisterBackend {
        var createCount = 0
        var notifyCount = 0
        override suspend fun identityExists(email: String): Boolean {
            if (throwOnCheck) throw IllegalStateException("admin down")
            if (email in existing) delay(checkDelayMs) // found → slow (hydration round-trips); miss → immediate
            return email in existing
        }
        override suspend fun createAndVerify(email: String, password: String) { createCount++ }
        override suspend fun notifyExisting(email: String) { notifyCount++ }
    }

    @Test
    fun bothBranches_padToFloor_maskingTheFoundMissAsymmetry() = runBlocking {
        val backend = TimedBackend(existing = setOf("taken@x.com"), checkDelayMs = FOUND_CHECK_MS)
        val m = RegisterMediator(backend, dispatch = { /* off-path */ }, floorMs = FLOOR)

        var takenOutcome: RegisterOutcome? = null
        var freshOutcome: RegisterOutcome? = null
        val tFound = measureTimeMillis { takenOutcome = m.register("taken@x.com", "pw") } // found → slow (but < floor)
        val tMiss = measureTimeMillis { freshOutcome = m.register("fresh@x.com", "pw") }  // miss  → fast

        assertEquals(RegisterOutcome.ACCEPTED, takenOutcome); assertEquals(RegisterOutcome.ACCEPTED, freshOutcome)
        // Load-bearing: the FAST (miss) branch is padded up to the floor — floorMs=0 would let it return early.
        assertTrue(tMiss >= FLOOR - 5, "the miss (fast) branch must be padded to the floor — was ${tMiss}ms")
        assertTrue(tFound >= FLOOR - 5, "the found (slow) branch is at/above the floor — was ${tFound}ms")
        // Buckets overlap: the ~25ms found/miss asymmetry is masked (both land near the floor, not 25ms apart).
        assertTrue(abs(tFound - tMiss) < FOUND_CHECK_MS, "found/miss must overlap after the floor — found=${tFound}ms miss=${tMiss}ms")
    }

    // ⭐ The leak-region teeth Test required (inverted FloorLeakDemo): a found check that runs PAST the floor is
    // cut to a UNIFORM 503 at ~floor — NEVER a slow-200 above the floor. A cap moved back above the floor (e.g.
    // `withTimeout(1000)` or a plain call) lets the check finish at 120ms → ACCEPTED@120ms → this test reds.
    @Test
    fun foundCheckPastTheFloor_isUniform503AtFloor_neverSlow200() = runBlocking {
        val floor = 60L
        val checkMs = 300L // a found check WELL past the floor; a wide gap makes the timing discriminator robust
        val backend = TimedBackend(existing = setOf("taken@x.com"), checkDelayMs = checkMs)
        val m = RegisterMediator(backend, dispatch = { }, floorMs = floor)

        var outcome: RegisterOutcome? = null
        val t = measureTimeMillis { outcome = m.register("taken@x.com", "pw") }
        // Primary teeth (mutation-catching): a cap moved back above the floor → the check finishes → ACCEPTED.
        assertEquals(RegisterOutcome.UNAVAILABLE, outcome, "a found check past the floor must be a UNIFORM 503, not a slow-200")
        // Confirmation: the 503 lands at ~floor, NOT at the found check's natural 300ms (wide gap tolerates JIT).
        assertTrue(t < 150, "the 503 must land at ~floor (~60ms), NOT at the found check's natural ${checkMs}ms — was ${t}ms")
        assertEquals(0, backend.createCount, "no create on a capped check (MUST-3)")
        assertEquals(0, backend.notifyCount, "no side-effect dispatched on a capped check")
    }

    @Test
    fun outage_isFloored_too_noFastTell() = runBlocking {
        val backend = TimedBackend(existing = emptySet(), checkDelayMs = 0, throwOnCheck = true)
        val m = RegisterMediator(backend, dispatch = { }, floorMs = FLOOR)
        var outcome: RegisterOutcome? = null
        val t = measureTimeMillis { outcome = m.register("x@x.com", "pw") }
        assertEquals(RegisterOutcome.UNAVAILABLE, outcome)
        assertTrue(t >= FLOOR - 5, "an outage 503 is floored too (no fast-503 vs slow-200 tell) — was ${t}ms")
    }
}
