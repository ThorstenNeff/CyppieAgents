package com.tneff.cyppieagents.boot

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.Closeable
import java.net.BindException
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-818 — the loopback boot-sentinel proven **through the real boot SEQUENCE** (Leg A / Leg B-LOCK of QA slice F4).
 *
 * This is the e2e/integration COMPLEMENT to Backend's `Cyp811LoopbackHubLockTest` unit teeth — deliberately NOT a
 * re-test of the lock primitive. The units (which inject `retain = {}` and call `acquireOrReject` in ISOLATION)
 * already cover the mechanics: same-IP reject, distinct-IP allow, tmpdir-independence, and the STRUCTURAL retention
 * (`isRooted`). What no unit exercises — and what the CYP-811 gate missed — is the primitive wired into the actual
 * `Application.kt` boot sequence (**acquire the sentinel, THEN start the hub's server**) and, above all, the **B-1
 * liveness under a REAL running server + GC**: the original bug was that the handle sat in a never-read local whose
 * liveness ends at its last use, so under `embeddedServer.start(wait=true)` GC collected it, the socket closed, the
 * port freed, and a 2nd hub booted. The units prove the handle is *placed* in the process-root; this proves that
 * root actually *survives* the collect-the-local + `.start()` + GC lifecycle that broke it. It compiles only on the
 * B-2 branch (the `sentinelPort` signature does not exist on develop), so it lands WITH CYP-818 as its e2e acceptance.
 *
 * Each case injects its OWN ephemeral `sentinelPort` (never the real 8818) so parallel/ordered cases never collide,
 * and clears the process-root after each case so a held sentinel never leaks into the next.
 */
class Cyp818LoopbackSentinelBootIntegrationTest {

    @AfterTest fun releaseAnyHeldSentinels() = LoopbackHubLock.clearRootsForTest()

    /** A currently-free loopback port to use as the test sentinel (avoids the real 8818 and cross-case collisions). */
    private fun freeSentinelPort(): Int = ServerSocket(0).use { it.localPort }

    /** A real hub server started AFTER the sentinel is acquired (the `Application.kt` order), on its own ephemeral port. */
    private fun startHubServer() = embeddedServer(Netty, port = 0) { }.start(wait = false)

    @Test
    fun sameLoopbackIp_twoRealHubBootSequences_secondRejectedBeforeItsServerStarts() {
        val port = freeSentinelPort()
        // Boot #1 — the real Application sequence: acquire the sentinel FIRST, then start the hub's server.
        val sentinel = LoopbackHubLock.acquireOrReject("127.0.0.1", port)
        assertNotNull(sentinel, "boot #1 acquires the loopback boot-sentinel")
        val hub1 = startHubServer()
        try {
            // Boot #2 — a SECOND hub on the SAME loopback IP runs the SAME sequence. Its sentinel bind conflicts →
            // boot is REJECTED before its server ever starts (the §9.5/§9.6 closure, through the boot sequence).
            val ex = assertFailsWith<IllegalStateException>(
                "a second hub on the same loopback IP is rejected at the boot sentinel, before it can start its server",
            ) { LoopbackHubLock.acquireOrReject("127.0.0.1", port, retain = {}) }
            assertTrue(ex.cause is BindException,
                "the reject is the sentinel-port bind conflict (BindException), not an unrelated failure")
        } finally {
            hub1.stop(0, 0)
        }
    }

    @Test
    fun sentinelSurvivesRealServerBootAndGc_theB1LivenessThroughTheRealBootLifecycle() {
        val port = freeSentinelPort()
        // Acquire with the DEFAULT retention (process-root) — exactly like Application.kt, which does NOT keep the
        // handle in a caller local. This is the axis the units do not drive (they inject `retain = {}`).
        val handle = LoopbackHubLock.acquireOrReject("127.0.0.1", port) ?: fail("loopback host must acquire")
        assertTrue(LoopbackHubLock.isRooted(handle),
            "B-1: the acquire path routes the live handle to the process-lifetime root (not a caller obligation)")
        val hub = startHubServer() // the real `.start()` lifecycle under which the original never-read local was collected
        try {
            // Drop the only caller reference and force GC + finalization — reproduce the collect-the-local lifecycle.
            @Suppress("UNUSED_VALUE", "UNUSED_VARIABLE")
            var local: Closeable? = handle
            local = null
            repeat(5) { System.gc(); System.runFinalization() }
            // The sentinel is STILL held (the process-root is a strong ref → the socket stayed open → the port stayed
            // bound) → a 2nd same-IP hub is STILL rejected. WITHOUT B-1's rooting, GC here would free the port and this
            // acquire would SUCCEED — the exact regression, now covered end-to-end under a live server.
            assertFailsWith<IllegalStateException>(
                "the sentinel survives GC while the hub server runs → the second hub stays rejected (B-1 holds end-to-end)",
            ) { LoopbackHubLock.acquireOrReject("127.0.0.1", port, retain = {}) }
        } finally {
            hub.stop(0, 0)
        }
    }

    @Test
    fun distinctLoopbackIps_twoRealHubBootSequences_bothBoot_separateCookieJars() {
        val port = freeSentinelPort()
        // Boot #1 on 127.0.0.1, server up.
        val a = LoopbackHubLock.acquireOrReject("127.0.0.1", port)
        assertNotNull(a, "boot #1 on 127.0.0.1 acquires")
        val hubA = startHubServer()
        try {
            // Boot #2 on a DISTINCT loopback IP (127.0.0.2) — a separate browser cookie jar (§9.5/§9.6), so the sentinel
            // binds a distinct address and the second hub COEXISTS: it acquires and its server starts, no reject.
            val b = LoopbackHubLock.acquireOrReject("127.0.0.2", port)
            assertNotNull(b, "a distinct loopback IP (127.0.0.2) is a separate cookie jar → the second hub coexists (boots)")
            val hubB = startHubServer()
            hubB.stop(0, 0)
        } finally {
            hubA.stop(0, 0)
        }
    }
}
