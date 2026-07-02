package com.tneff.cyppieagents.auth

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-179 / §B(b) — the register-wrapper MUSTs, hermetic + deterministic (the security property does not
 * depend on the real Kratos mail mechanics). A controllable [dispatch] captures the branch-divergent side-
 * effect so the tests can prove it is DEFERRED (timing parity) and then run it to inspect the effect.
 */
class RegisterWrapperTest {

    private class FakeRegisterBackend(
        existing: Set<String> = emptySet(),
        private val failCheck: Boolean = false,
    ) : KratosRegisterBackend {
        val store = existing.toMutableSet()
        var checkCount = 0
        var createCount = 0
        var notifyCount = 0
        var lastCreated: String? = null
        var lastNotified: String? = null

        override suspend fun identityExists(email: String): Boolean {
            checkCount++
            if (failCheck) throw IllegalStateException("admin :4434 down")
            return email in store
        }

        override suspend fun createAndVerify(email: String, password: String) {
            createCount++; store += email; lastCreated = email
        }

        override suspend fun notifyExisting(email: String) {
            notifyCount++; lastNotified = email
        }
    }

    // MUST-2 (content parity): a NEW and an EXISTING email yield the IDENTICAL verdict (→ same HTTP response).
    @Test
    fun contentParity_newAndExisting_bothAccepted() = runBlocking {
        val backend = FakeRegisterBackend(existing = setOf("taken@x.com"))
        val m = RegisterMediator(backend, dispatch = { /* drop */ })
        assertEquals(RegisterOutcome.ACCEPTED, m.register("fresh@x.com", "pw"))
        assertEquals(RegisterOutcome.ACCEPTED, m.register("taken@x.com", "pw"))
    }

    // MUST-2 (timing parity): the branch-DIVERGENT work is deferred — at the moment register() returns, neither
    // create nor notify has run (only the symmetric existence check). True for BOTH branches → latency can't leak.
    @Test
    fun timingParity_sideEffectDeferred_forBothBranches() = runBlocking {
        val queued = mutableListOf<suspend () -> Unit>()
        val backend = FakeRegisterBackend(existing = setOf("taken@x.com"))
        val m = RegisterMediator(backend, dispatch = { queued += it })

        m.register("fresh@x.com", "pw")
        assertEquals(1, backend.checkCount, "existence check ran on the sync path")
        assertEquals(0, backend.createCount, "create must be DEFERRED off the response path (new branch)")
        assertEquals(0, backend.notifyCount, "notify must be DEFERRED off the response path")

        m.register("taken@x.com", "pw")
        assertEquals(2, backend.checkCount, "existence check ran again (symmetric — same sync work both branches)")
        assertEquals(0, backend.createCount, "no branch does divergent work on the response path")
        assertEquals(0, backend.notifyCount, "existing branch's notify is DEFERRED too")

        // Draining the deferred work now applies the correct per-branch effect.
        queued.forEach { it() }
        assertEquals(1, backend.createCount); assertEquals("fresh@x.com", backend.lastCreated)
        assertEquals(1, backend.notifyCount); assertEquals("taken@x.com", backend.lastNotified)
    }

    // MUST-2 (no-leak / count invariant): an EXISTING-email register creates NO identity (no dup / no
    // store-pollution); a NEW-email register creates exactly one.
    @Test
    fun noLeak_existingCreatesNothing_newCreatesOne() = runBlocking {
        val queued = mutableListOf<suspend () -> Unit>()
        val backend = FakeRegisterBackend(existing = setOf("taken@x.com"))
        val m = RegisterMediator(backend, dispatch = { queued += it })

        m.register("taken@x.com", "pw"); queued.forEach { it() }; queued.clear()
        assertEquals(0, backend.createCount, "existing email must NOT create an identity (no pollution/DoS)")
        assertEquals(setOf("taken@x.com"), backend.store, "the identity store is UNCHANGED for an existing email")

        m.register("fresh@x.com", "pw"); queued.forEach { it() }
        assertEquals(1, backend.createCount, "new email creates exactly one identity")
        assertTrue("fresh@x.com" in backend.store)
    }

    // MUST-3 (fail-closed): an existence-check outage → UNIFORM UNAVAILABLE for BOTH branches, and NO create/
    // notify is even dispatched (no create on a failed check; no branch-dependent error).
    @Test
    fun failClosed_adminOutage_uniformUnavailable_noDispatch() = runBlocking {
        val queued = mutableListOf<suspend () -> Unit>()
        val backend = FakeRegisterBackend(existing = setOf("taken@x.com"), failCheck = true)
        val m = RegisterMediator(backend, dispatch = { queued += it })

        assertEquals(RegisterOutcome.UNAVAILABLE, m.register("fresh@x.com", "pw"))
        assertEquals(RegisterOutcome.UNAVAILABLE, m.register("taken@x.com", "pw"), "outage is uniform across branches")
        assertTrue(queued.isEmpty(), "no side-effect may be dispatched when the check failed (no create)")
        assertEquals(0, backend.createCount); assertEquals(0, backend.notifyCount)
        assertNull(backend.lastCreated)
    }
}
