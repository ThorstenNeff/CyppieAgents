package com.tneff.cyppieagents.boot

import java.io.Closeable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-811 — the boot-time fail-closed lock: a second hub on the SAME loopback IP is REJECTED at boot (the on-loopback
 * cookie-jar-sharing hole, §9.5). Non-vacuous: the FIRST acquire succeeds (positive control — not always-reject), a
 * SECOND on the same IP throws (the reject), a DISTINCT loopback IP is allowed (separate cookie jars), and an
 * off-loopback host is not applicable (null). Mutation: remove the `throw` in [LoopbackHubLock.acquireOrReject] → the
 * second acquire returns instead of rejecting → `secondHubSameLoopbackIp_rejected` reds (green-but-dead caught).
 *
 * The lock-mechanics cases inject `retain = {}` so they exercise acquire/reject WITHOUT the production retention side
 * effects (shutdown-hook registration + the process-global root) — retention is orthogonal to acquire/reject and has
 * its own teeth below. They keep the handle in a live local, so the lock is held for the duration regardless.
 *
 * **CYP-818 B-1 (liveness) teeth** — [acquire_routesHandleToRetention] + [defaultRetention_rootsHandleForProcessLifetime]:
 * the returned handle MUST be routed to a process-lifetime GC root, else it is collectable (its last use ends at the
 * assignment, not scope-end) → the JDK `FileChannel` Cleaner closes the fd → the advisory lock frees → a 2nd same-IP
 * hub boots → the replay vector reopens. These pin the retention STRUCTURALLY (no GC race).
 */
class Cyp811LoopbackHubLockTest {

    private val dir = Files.createTempDirectory("cyp811-lock")

    @Test
    fun firstHubAcquires_secondHubSameLoopbackIp_rejected_thenReleaseAllowsReacquire() {
        // positive control: the FIRST hub on 127.0.0.1 takes the lock.
        val first = LoopbackHubLock.acquireOrReject("127.0.0.1", dir, retain = {})
        assertNotNull(first, "the first hub on a loopback IP acquires the boot lock")
        // ★ the reject: a SECOND hub on the SAME loopback IP → boot rejected (shared cookie jar → replay).
        assertFailsWith<IllegalStateException> { LoopbackHubLock.acquireOrReject("127.0.0.1", dir, retain = {}) }
        // releasing the first frees the IP → a later hub may re-acquire (the lock is process-lifetime, not permanent).
        first.close()
        val reacquired = LoopbackHubLock.acquireOrReject("127.0.0.1", dir, retain = {})
        assertNotNull(reacquired); reacquired.close()
    }

    @Test
    fun distinctLoopbackIp_isAllowed_separateCookieJars() {
        val a = LoopbackHubLock.acquireOrReject("127.0.0.1", dir, retain = {})
        assertNotNull(a)
        // 127.0.0.2 is a DIFFERENT loopback IP → a different cookie jar → allowed to co-exist.
        val b = LoopbackHubLock.acquireOrReject("127.0.0.2", dir, retain = {})
        assertNotNull(b, "a distinct loopback IP is a separate cookie jar — co-existence is safe")
        a.close(); b.close()
    }

    @Test
    fun offLoopbackHost_isNotApplicable_null() {
        // off-loopback: S-AAL2b already disables the browser-operator posture → no cookie-jar hole → no lock needed.
        assertNull(LoopbackHubLock.acquireOrReject("0.0.0.0", dir, retain = {}))
        assertNull(LoopbackHubLock.acquireOrReject("192.168.1.10", dir, retain = {}))
    }

    // ---- CYP-818 B-1: the liveness axis the CYP-811 gate missed ----

    @Test
    fun acquire_routesHandleToRetention_soItOutlivesTheCall() {
        // ★ B-1 liveness: acquireOrReject MUST hand the LIVE lock handle to a retention root. Without it, the handle is
        // GC-collectable the instant acquire returns (a never-read local's liveness ends at its last use), the JDK
        // FileChannel Cleaner frees the lock, and a 2nd same-IP hub boots. MUT: drop `retain(handle)` in
        // acquireOrReject → `captured` stays empty → this reds.
        val captured = mutableListOf<Closeable>()
        val handle = LoopbackHubLock.acquireOrReject("127.0.0.1", dir, retain = { captured.add(it) })
        assertNotNull(handle, "positive control: the first hub acquires")
        assertEquals(listOf(handle), captured, "acquireOrReject must route the live lock handle to the retention root (B-1)")
        handle.close()
    }

    @Test
    fun defaultRetention_rootsHandleForProcessLifetime() {
        // The DEFAULT rooting places the handle in the object-field GC root → reachable via the loaded class for the
        // whole process → never collectable ⟹ the FileChannel stays open ⟹ the FileLock is held. MUT: make
        // `rootForProcessLifetime` a no-op → `isRooted` false → this reds. (Uses rootForProcessLifetime directly, not
        // the shutdown-hook-registering default, so the JVM's shutdown-hook set is not polluted.)
        try {
            val handle = LoopbackHubLock.acquireOrReject("127.0.0.1", dir, retain = LoopbackHubLock::rootForProcessLifetime)
            assertNotNull(handle)
            assertTrue(LoopbackHubLock.isRooted(handle), "the default retention must root the handle in the process-lifetime field (B-1)")
        } finally {
            LoopbackHubLock.clearRootsForTest()
        }
    }
}
