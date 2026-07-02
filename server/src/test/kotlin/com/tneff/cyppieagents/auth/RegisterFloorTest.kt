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
 * is SLOW for a found email (hydration round-trips) and FAST for a miss (short-circuit). The floor must pad BOTH
 * branches up to the same minimum so the found/miss latency tell never reaches the wire. The load-bearing teeth
 * is that the **fast (miss) branch is padded up too** — a `floorMs = 0` mutation reds it (the miss returns early).
 */
class RegisterFloorTest {

    private val FLOOR = 80L
    private val FOUND_CHECK_MS = 25L // the "found" existence check is slow (simulated hydration round-trips)

    /** identityExists: slow+true for a seeded email, fast+false otherwise; optional throw/hang to model outage. */
    private class TimedBackend(
        private val existing: Set<String>,
        private val checkDelayMs: Long,
        private val throwOnCheck: Boolean = false,
        private val hangMs: Long = 0,
    ) : KratosRegisterBackend {
        var createCount = 0
        override suspend fun identityExists(email: String): Boolean {
            if (throwOnCheck) throw IllegalStateException("admin down")
            if (hangMs > 0) { delay(hangMs); return email in existing }
            if (email in existing) delay(checkDelayMs) // found → slow (hydration); miss → immediate
            return email in existing
        }
        override suspend fun createAndVerify(email: String, password: String) { createCount++ }
        override suspend fun notifyExisting(email: String) {}
    }

    @Test
    fun bothBranches_padToFloor_maskingTheFoundMissAsymmetry() = runBlocking {
        val backend = TimedBackend(existing = setOf("taken@x.com"), checkDelayMs = FOUND_CHECK_MS)
        val m = RegisterMediator(backend, dispatch = { /* off-path */ }, floorMs = FLOOR)

        var takenOutcome: RegisterOutcome? = null
        var freshOutcome: RegisterOutcome? = null
        val tFound = measureTimeMillis { takenOutcome = m.register("taken@x.com", "pw") } // found → slow check
        val tMiss = measureTimeMillis { freshOutcome = m.register("fresh@x.com", "pw") }  // miss  → fast check

        assertEquals(RegisterOutcome.ACCEPTED, takenOutcome); assertEquals(RegisterOutcome.ACCEPTED, freshOutcome)
        // Load-bearing: the FAST (miss) branch is padded up to the floor — floorMs=0 would let it return early.
        assertTrue(tMiss >= FLOOR - 5, "the miss (fast) branch must be padded to the floor — was ${tMiss}ms")
        assertTrue(tFound >= FLOOR - 5, "the found (slow) branch is at/above the floor — was ${tFound}ms")
        // Buckets overlap: the ~25ms found/miss asymmetry is masked (both land near the floor, not 25ms apart).
        assertTrue(abs(tFound - tMiss) < FOUND_CHECK_MS, "found/miss must overlap after the floor — found=${tFound}ms miss=${tMiss}ms")
    }

    @Test
    fun clamp_hangBeyondClamp_uniformUnavailable_stillFloored_noCreate() = runBlocking {
        // The check hangs (150ms) past the clamp (40ms) → uniform 503; the floor still applies (found/miss-blind).
        val backend = TimedBackend(existing = setOf("taken@x.com"), checkDelayMs = 0, hangMs = 150)
        val m = RegisterMediator(backend, dispatch = { }, floorMs = FLOOR, clampMs = 40)

        var outcome: RegisterOutcome? = null
        val t = measureTimeMillis { outcome = m.register("taken@x.com", "pw") }
        assertEquals(RegisterOutcome.UNAVAILABLE, outcome, "a check slower than the clamp → uniform outage")
        assertTrue(t >= FLOOR - 5, "even the outage response is floored (found/miss-blind) — was ${t}ms")
        assertEquals(0, backend.createCount, "no create on a clamped/failed check (MUST-3)")
    }

    @Test
    fun outage_isFloored_too() = runBlocking {
        val backend = TimedBackend(existing = emptySet(), checkDelayMs = 0, throwOnCheck = true)
        val m = RegisterMediator(backend, dispatch = { }, floorMs = FLOOR)
        var outcome: RegisterOutcome? = null
        val t = measureTimeMillis { outcome = m.register("x@x.com", "pw") }
        assertEquals(RegisterOutcome.UNAVAILABLE, outcome)
        assertTrue(t >= FLOOR - 5, "an outage 503 is floored too (no fast-503 vs slow-200 tell) — was ${t}ms")
    }
}
