package com.tneff.cyppieagents.boot

import java.io.Closeable
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-811 / CYP-818 — the boot-time fail-closed lock: a second hub on the SAME loopback IP is REJECTED at boot (the
 * on-loopback cookie-jar-sharing hole, §9.5/§9.6). Non-vacuous: the FIRST acquire succeeds (positive control — not
 * always-reject), a SECOND on the same IP throws (the reject), a DISTINCT loopback IP is allowed (separate cookie
 * jars), and an off-loopback host is not applicable (null). Mutation: remove the `throw` in
 * [LoopbackHubLock.acquireOrReject] → the second acquire returns instead of rejecting → `firstHubAcquires…` reds.
 *
 * Each case injects its own ephemeral [sentinelPort] so parallel/ordering never has two unrelated cases collide on the
 * real [LoopbackHubLock.SENTINEL_PORT]; the mechanics cases inject `retain = {}` so they exercise acquire/reject
 * WITHOUT the production retention side effects (retention has its own B-1 teeth). They keep the handle in a live
 * local, so the port is held for the duration regardless.
 *
 * **CYP-818 B-2 (net-NS, tmpdir-independent)** — [sameLoopbackIp_rejected_regardlessOfTmpdir]: the lock is keyed by the
 * network namespace (a loopback-IP socket bind), NOT `java.io.tmpdir`, so two same-IP hubs with DIFFERENT tmpdirs
 * (the `PrivateTmp` / `-Djava.io.tmpdir` scenario that defeated the old `/tmp` FileLock) still contend.
 * **CYP-818 B-1 (liveness)** — [acquire_routesHandleToRetention] + [defaultRetention_rootsHandleForProcessLifetime]:
 * the returned handle MUST be routed to a process-lifetime GC root, else it is collectable → the ServerSocket closes →
 * the port frees → a 2nd same-IP hub boots. These pin the retention STRUCTURALLY (no GC race).
 */
class Cyp811LoopbackHubLockTest {

    // a currently-free port for this test method (fresh instance per method); avoids the fixed production sentinel.
    private val port = ServerSocket(0).use { it.localPort }

    @Test
    fun firstHubAcquires_secondHubSameLoopbackIp_rejected_thenReleaseAllowsReacquire() {
        // positive control: the FIRST hub on 127.0.0.1 takes the lock.
        val first = LoopbackHubLock.acquireOrReject("127.0.0.1", port, retain = {})
        assertNotNull(first, "the first hub on a loopback IP acquires the boot lock")
        // ★ the reject: a SECOND hub on the SAME loopback IP → boot rejected (shared cookie jar → replay).
        assertFailsWith<IllegalStateException> { LoopbackHubLock.acquireOrReject("127.0.0.1", port, retain = {}) }
        // releasing the first frees the IP → a later hub may re-acquire (the lock is process-lifetime, not permanent).
        first.close()
        val reacquired = LoopbackHubLock.acquireOrReject("127.0.0.1", port, retain = {})
        assertNotNull(reacquired); reacquired.close()
    }

    @Test
    fun distinctLoopbackIp_isAllowed_separateCookieJars() {
        val a = LoopbackHubLock.acquireOrReject("127.0.0.1", port, retain = {})
        assertNotNull(a)
        // 127.0.0.2 is a DIFFERENT loopback IP → a different address bind → a different cookie jar → allowed to co-exist.
        val b = LoopbackHubLock.acquireOrReject("127.0.0.2", port, retain = {})
        assertNotNull(b, "a distinct loopback IP is a separate cookie jar — co-existence is safe")
        a.close(); b.close()
    }

    @Test
    fun offLoopbackHost_isNotApplicable_null() {
        // off-loopback: S-AAL2b already disables the browser-operator posture → no cookie-jar hole → no lock needed.
        assertNull(LoopbackHubLock.acquireOrReject("0.0.0.0", port, retain = {}))
        assertNull(LoopbackHubLock.acquireOrReject("192.168.1.10", port, retain = {}))
    }

    // ---- CYP-818 B-2: the lock is net-NS-keyed, NOT tmpdir-keyed (the PrivateTmp/ProtectSystem-proof property) ----

    @Test
    fun sameLoopbackIp_rejected_regardlessOfTmpdir() {
        val a = LoopbackHubLock.acquireOrReject("127.0.0.1", port, retain = {})
        assertNotNull(a, "positive control: the first hub acquires")
        // ★ B-2: give the "2nd hub" a DIFFERENT java.io.tmpdir — the exact scenario (`-Djava.io.tmpdir`, and the
        // moral equivalent of PrivateTmp's per-service /tmp) that put a /tmp FileLock's two locks in different files →
        // both boot (the B-2 hole). The net-NS sentinel ignores tmpdir → the 2nd same-IP hub is STILL rejected.
        // MUT: revert acquireOrReject to a FileLock under java.io.tmpdir → the 2nd (different tmpdir) acquires → no
        // throw → this reds. (The true PrivateTmp separate-namespace case cannot be unit-tested; the same-IP-rejected
        // path above closes it by construction — the network namespace is shared exactly when the cookie jar is.)
        val savedTmp = System.getProperty("java.io.tmpdir")
        val altTmp = Files.createTempDirectory("cyp818-b2-alt-tmpdir")
        try {
            System.setProperty("java.io.tmpdir", altTmp.toString())
            assertFailsWith<IllegalStateException> { LoopbackHubLock.acquireOrReject("127.0.0.1", port, retain = {}) }
        } finally {
            System.setProperty("java.io.tmpdir", savedTmp)
            a.close()
        }
    }

    // ---- CYP-818 B-1: the liveness axis the CYP-811 gate missed ----

    @Test
    fun acquire_routesHandleToRetention_soItOutlivesTheCall() {
        // ★ B-1 liveness: acquireOrReject MUST hand the LIVE lock handle to a retention root. Without it, the handle is
        // GC-collectable the instant acquire returns (a never-read local's liveness ends at its last use), the
        // ServerSocket closes, the port frees, and a 2nd same-IP hub boots. MUT: drop `retain(handle)` in
        // acquireOrReject → `captured` stays empty → this reds.
        val captured = mutableListOf<Closeable>()
        val handle = LoopbackHubLock.acquireOrReject("127.0.0.1", port, retain = { captured.add(it) })
        assertNotNull(handle, "positive control: the first hub acquires")
        assertEquals(listOf(handle), captured, "acquireOrReject must route the live lock handle to the retention root (B-1)")
        handle.close()
    }

    @Test
    fun defaultRetention_rootsHandleForProcessLifetime() {
        // The DEFAULT rooting places the handle in the object-field GC root → reachable via the loaded class for the
        // whole process → never collectable ⟹ the ServerSocket stays open ⟹ the port is held. MUT: make
        // `rootForProcessLifetime` a no-op → `isRooted` false → this reds. (Uses rootForProcessLifetime directly, not
        // the shutdown-hook-registering default, so the JVM's shutdown-hook set is not polluted.)
        try {
            val handle = LoopbackHubLock.acquireOrReject("127.0.0.1", port, retain = LoopbackHubLock::rootForProcessLifetime)
            assertNotNull(handle)
            assertTrue(LoopbackHubLock.isRooted(handle), "the default retention must root the handle in the process-lifetime field (B-1)")
        } finally {
            LoopbackHubLock.clearRootsForTest()
        }
    }
}
